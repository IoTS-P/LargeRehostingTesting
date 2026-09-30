#!/usr/bin/env python3
"""
uEmu 固件 fuzz 流水线

    firmware/<batch>/<fw>/{<fw>.elf, <fw>.cfg}      输入
    result/<batch>/                                  该批次全部结果
      ├── manifest.json       参数快照
      ├── kb_status.csv       每固件一行：KB 阶段
      ├── results.csv         每固件一行：分类结果
      ├── coverage.csv        每固件一行：覆盖率
      ├── logs/               evidence.jsonl / kb.log / fuzz.log
      ├── kb/<fw>/            KB 产物
      └── fuzz/<fw>/          fuzz 产物

uEmu 路径从环境变量 uEmuDIR 取。
"""
import argparse
import csv
import glob
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

try:
    import psutil
except ImportError:
    psutil = None

ROOT = os.path.dirname(os.path.abspath(__file__))
CFG_TIMEOUT = 120          # 单次 helper 配置生成超时（秒）


# ────────────────────────────── 配置 ──────────────────────────────

def load_config():
    path = os.path.join(ROOT, "pipeline.yaml")
    if not os.path.exists(path):
        sys.exit(f"缺少配置文件: {path}")
    try:
        import yaml
    except ImportError:
        sys.exit("需要 PyYAML:  pip install pyyaml")
    with open(path) as f:
        cfg = yaml.safe_load(f)

    uemu = os.environ.get("uEmuDIR")
    if not uemu:
        sys.exit("未设置环境变量 uEmuDIR（例: export uEmuDIR=/home/czs/uEmu）")
    if not os.path.isdir(uemu):
        sys.exit(f"uEmuDIR 不存在: {uemu}")

    cfg["_uemu_dir"] = uemu
    cfg["_root"] = ROOT
    return cfg


CFG = None
_log_lock = threading.Lock()


def log(msg, fh=None):
    line = f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {msg}"
    with _log_lock:
        print(line, flush=True)
        if fh:
            fh.write(line + "\n")
            fh.flush()


def log_path(batch, name):
    d = os.path.join(CFG["_root"], CFG["paths"]["result"], batch, "logs")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, name)


# ────────────────────────────── 路径 ──────────────────────────────

def firmware_dir(batch):
    return os.path.join(ROOT, CFG["paths"]["firmware"], batch)


def result_dir(batch):
    return os.path.join(ROOT, CFG["paths"]["result"], batch)


def kb_dir(batch, fw=None):
    d = os.path.join(result_dir(batch), "kb")
    return os.path.join(d, fw) if fw else d


def fuzz_dir(batch, fw=None):
    d = os.path.join(result_dir(batch), "fuzz")
    return os.path.join(d, fw) if fw else d


def list_firmware(batch, elfs=None):
    d = firmware_dir(batch)
    if not os.path.isdir(d):
        sys.exit(f"固件目录不存在: {d}")
    ids = sorted([x for x in os.listdir(d) if x.isdigit()], key=int)
    if elfs:
        want = [x.strip() for x in elfs.split(",") if x.strip()]
        missing = [x for x in want if x not in ids]
        if missing:
            sys.exit(f"--elfs 指定的固件不存在: {', '.join(missing)}")
        ids = [x for x in ids if x in want]
    return ids


def find_elf(batch, fw):
    d = os.path.join(firmware_dir(batch), fw)
    hits = glob.glob(os.path.join(d, "*.elf"))
    return hits[0] if hits else None


def find_cfg(batch, fw):
    d = os.path.join(firmware_dir(batch), fw)
    hits = glob.glob(os.path.join(d, "*.cfg"))
    return hits[0] if hits else None


def find_kb(batch, fw):
    d = kb_dir(batch, fw)
    if not os.path.isdir(d):
        return None
    hits = sorted(glob.glob(os.path.join(d, CFG.get("kb_glob", "*_KB.dat"))))
    return hits[0] if hits else None


# ────────────────────────────── 工具 ──────────────────────────────

def kill_tree(proc, use_sigint=False, sigint_wait=15):
    """杀掉整个进程树。use_sigint=True 时先 SIGINT，等待后再 SIGKILL。"""
    if proc is None:
        return
    if psutil is None:
        proc.kill()
        proc.wait()
        return
    try:
        parent = psutil.Process(proc.pid)
        children = parent.children(recursive=True)
    except psutil.NoSuchProcess:
        return

    for p in children + [parent]:
        try:
            p.send_signal(signal.SIGINT) if use_sigint else p.kill()
        except psutil.NoSuchProcess:
            pass

    if use_sigint:
        try:
            proc.wait(timeout=sigint_wait)
            return
        except subprocess.TimeoutExpired:
            pass
    try:
        for p in psutil.Process(proc.pid).children(recursive=True) + [psutil.Process(proc.pid)]:
            try:
                p.kill()
            except psutil.NoSuchProcess:
                pass
    except psutil.NoSuchProcess:
        pass
    try:
        proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        pass


def gen_launch_scripts(elf, cfg_file, kb_file=None, seed=None, outdir=None):
    """
    调 uEmu-helper.py 生成 launch-uEmu.sh / uEmu-config.lua / (launch-AFL.sh)。
    helper 的输出和模板都读写 cwd，所以给它一个独立临时目录 —— 无锁、可并行。
    返回 (成功?, 产物列表, 错误信息)
    """
    uemu = CFG["_uemu_dir"]
    # helper 的输入模板和输出都走 cwd，所以给每次调用一个独立临时目录：
    # 无全局锁、可并行，也不会互相覆盖。
    tmp = tempfile.mkdtemp(prefix="uemu-cfg-")
    try:
        # 模板 + library.lua（uEmu-config.lua 里 dofile('library.lua') 要它在 cwd）
        for t in ("launch-AFL-template.sh", "launch-uEmu-template.sh",
                  "uEmu-config-template.lua", "library.lua"):
            src = os.path.join(uemu, t)
            if os.path.exists(src):
                shutil.copy2(src, tmp)
        cmd = ["python3", os.path.join(uemu, "uEmu-helper.py"),
               os.path.abspath(elf), os.path.abspath(cfg_file)]
        if kb_file:
            cmd += ["-kb", os.path.basename(kb_file)]
        if seed:
            cmd += ["-s", os.path.abspath(seed)]
        env = {**os.environ, "uEmuDIR": uemu}
        r = subprocess.run(cmd, cwd=tmp, env=env, timeout=CFG_TIMEOUT,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if r.returncode != 0:
            return False, [], (r.stdout or "")[-400:]
        made = []
        for f in ("launch-uEmu.sh", "uEmu-config.lua", "launch-AFL.sh", "library.lua"):
            p = os.path.join(tmp, f)
            if os.path.isfile(p):
                if outdir:
                    shutil.copy2(p, os.path.join(outdir, f))
                made.append(f)
        if not made:
            return False, [], "helper 未产出任何文件"
        return True, made, ""
    except Exception as e:
        return False, [], str(e)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def patch_launch_afl(path, case_dir, elf_name):
    """
    模板把 AFL/ 前缀拼到绝对路径上，渲染结果是坏的（AFL//abs/path），
    且用的是裸 afl-fuzz。这里改写成绝对路径 + 显式二进制。
    """
    if not os.path.exists(path):
        return
    with open(path) as f:
        txt = f.read()
    txt = re.sub(r'FUZZ_IN="[^"]*"', f'FUZZ_IN="{case_dir}/AFL/{elf_name}_fuzz_in"', txt)
    txt = re.sub(r'FUZZ_OUT="[^"]*"', f'FUZZ_OUT="{case_dir}/AFL/{elf_name}_fuzz_out"', txt)
    txt = re.sub(r'^\s*afl-fuzz ', f'\t{CFG["afl_bin"]} ', txt, flags=re.M)
    txt = re.sub(r'^\s*(/[\w./-]*/)afl-fuzz ', f'\t{CFG["afl_bin"]} ', txt, flags=re.M)
    if not txt.endswith("\n"):
        txt += "\n"
    with open(path, "w") as f:
        f.write(txt)
    os.chmod(path, 0o755)


# ────────────────────────── debug.txt 摘要 ──────────────────────────

SUMMARY_VERSION = "v2"
HANG_PC_LIMIT = 10          # 保留的 hang 地址个数
KILL_LINE_LIMIT = 5         # 每类 kill 原文最多保留几行

RE_KB = re.compile(r"KB Extraction Phase Finish")
RE_AR = re.compile(r"AFL Reconnection")
RE_FK = re.compile(r"fork point")
RE_MS = re.compile(r"mode switch!!")
RE_NP = re.compile(r"New peripheral")
RE_KF = re.compile(r"Kill Fuzz State")
RE_INV = re.compile(r"Invalid memory")
RE_KP = re.compile(r"Kill path")
RE_HANG = re.compile(r"hang at pc")
RE_CM = re.compile(r"concrete mode: ")
# 4 种 loop 消息（此前只覆盖到 1 种）
RE_DL = re.compile(r"Kill State due to dead loop \(|Kill State due to Dead Loop \(")
RE_LL = re.compile(r"Kill State due to long loop \(")
RE_KILL_REASON = re.compile(r"Kill State due to ([A-Za-z][^(]*?)\s*\(")
RE_HANG_PC = re.compile(r"hang at pc = (0x[0-9a-fA-F]+)")
RE_TERM = [(re.compile(r"Testing aborted by user via Fuzzer"), "Testing aborted by user via Fuzzer"),
           (re.compile(r"Engine terminated"), "Engine terminated"),
           (re.compile(r"Learning state can not be switch to fuzzing state"),
            "Learning state can not be switch to fuzzing state")]


def build_summary(debug_txt, uemu_log, out_path):
    """
    单遍扫 debug.txt，产出摘要 v2：
      - 计数：KT AR FK DL LL HANG NP KF INV KP mode_switch
      - 行号：各类最后/首次出现位置
      - 原文：kill 原因、终止消息、最后 concrete mode
      - 分布：hang 的 PC 地址 TopN、kill 原因族计数
    """
    n = dict(kb=0, ar=0, fk=0, dl=0, ll=0, hang=0, np=0, kf=0, inv=0, kp=0, ms=0)
    ln = dict(kb=0, ar=0, fk=0, dl_first=0, dl_last=0, hang_first=0, hang_last=0, ms=0)
    kf_lines, kp_lines, kill_reasons = [], [], {}
    hang_pc = {}
    cm_line = ""
    terms = []
    nlines = 0

    with open(debug_txt, errors="ignore") as f:
        for i, line in enumerate(f, 1):
            nlines = i
            if RE_KB.search(line):      n["kb"] += 1; ln["kb"] = i
            if RE_AR.search(line):      n["ar"] += 1; ln["ar"] = i
            if RE_FK.search(line):      n["fk"] += 1; ln["fk"] = i
            if RE_MS.search(line):      n["ms"] += 1; ln["ms"] = i
            if RE_NP.search(line):      n["np"] += 1
            if RE_KF.search(line):
                n["kf"] += 1
                if len(kf_lines) < KILL_LINE_LIMIT: kf_lines.append(line.strip())
            if RE_INV.search(line):     n["inv"] += 1
            if RE_KP.search(line):
                n["kp"] += 1
                if len(kp_lines) < KILL_LINE_LIMIT: kp_lines.append(line.strip())
            if RE_DL.search(line):
                n["dl"] += 1
                if not ln["dl_first"]: ln["dl_first"] = i
                ln["dl_last"] = i
            if RE_LL.search(line):
                n["ll"] += 1
                if not ln["dl_first"]: ln["dl_first"] = i
                ln["dl_last"] = i
            if RE_HANG.search(line):
                n["hang"] += 1
                if not ln["hang_first"]: ln["hang_first"] = i
                ln["hang_last"] = i
                m = RE_HANG_PC.search(line)
                if m:
                    hang_pc[m.group(1)] = hang_pc.get(m.group(1), 0) + 1
            if RE_CM.search(line):
                cm_line = line.strip()
            m = RE_KILL_REASON.search(line)
            if m:
                k = m.group(1).strip()
                kill_reasons[k] = kill_reasons.get(k, 0) + 1
            for rx, name in RE_TERM:
                if rx.search(line) and name not in terms:
                    terms.append(name)

    segfault = 0
    if uemu_log and os.path.isfile(uemu_log):
        with open(uemu_log, errors="ignore") as f:
            for line in f:
                if "Segmentation fault" in line:
                    segfault += 1

    size = os.path.getsize(debug_txt)
    with open(out_path, "w") as f:
        f.write(f"# debug.txt摘要 {SUMMARY_VERSION} (pipeline.py 生成)\n")
        f.write(f"debug: {os.path.relpath(debug_txt, os.path.dirname(out_path))}\n")
        f.write(f"lines: {nlines}\n")
        f.write(f"bytes: {size}\n")
        f.write(f"SEGFAULT: {segfault}\n")
        for k in ("KB", "AR", "FK", "DL", "LL", "HANG", "NP", "KF", "INV", "KP"):
            f.write(f"{k}: {n[k.lower()]}\n")
        f.write(f"mode_switch: {n['ms']}\n")
        f.write(f"kb_last_ln: {ln['kb']}\n")
        f.write(f"ar_last_ln: {ln['ar']}\n")
        f.write(f"fk_last_ln: {ln['fk']}\n")
        f.write(f"dl_first_ln: {ln['dl_first']}\n")
        f.write(f"dl_last_ln: {ln['dl_last']}\n")
        f.write(f"hang_first_ln: {ln['hang_first']}\n")
        f.write(f"hang_last_ln: {ln['hang_last']}\n")
        f.write(f"ms_last_ln: {ln['ms']}\n")
        if cm_line:
            f.write(f"concrete_mode: {cm_line}\n")
        if kf_lines:
            f.write(f"kf_line: {kf_lines[0]}\n")
        if kp_lines:
            f.write(f"kp_line: {kp_lines[0]}\n")
        for r, c in sorted(kill_reasons.items(), key=lambda x: -x[1]):
            f.write(f"kill_reason: {r} = {c}\n")
        for pc, c in sorted(hang_pc.items(), key=lambda x: -x[1])[:HANG_PC_LIMIT]:
            f.write(f"hang_pc: {pc} = {c}\n")
        if terms:
            f.write(f"termination: {','.join(terms)}\n")
    return True


def read_summary(summary_path):
    if not os.path.exists(summary_path):
        return None
    kv = {}
    for line in open(summary_path, errors="ignore"):
        if line.startswith("#") or ":" not in line:
            continue
        k, v = line.split(":", 1)
        kv[k.strip()] = v.strip()
    return kv


# ────────────────────────── cfg 生成 ──────────────────────────

CFG_TEMPLATE = """[MEM_Config]
rom = {rom},{rom_len}
ram = 0x20000000,0x50000
vtor = {vtor}

[IRQ_Config]
irq_tb_break = 1000
disable_systick = false
disable_irqs =

[INV_Config]
bb_inv1 = 20
bb_inv2 = 2000
bb_terminate = 30000
kill_points =
alive_points =

[TC_Config]
t2_function_parameter_num = 3
t2_caller_level = 3
t2_max_context = 8
t3_max_symbolic_count = 100

[Fuzzer_Config]
input_peripherals =
enable_fuzz = true
allow_auto_mode_switch = true
additional_writable_ranges =
time_out = 10
crash_points =
allow_new_phs = true
fork_count = 1000
"""


def detect_mem(elf):
    """从 LOAD 段探测 ROM 基址与长度（沿用 config.sh 的策略）。"""
    rom, rom_len = "0x08000000", "0x20000"
    try:
        out = subprocess.run(["readelf", "-l", elf], capture_output=True, text=True, timeout=30).stdout
    except Exception:
        return rom, rom_len
    for line in out.splitlines():
        if "LOAD" in line:
            parts = line.split()
            # 典型: LOAD 0x000000 0x08000000 0x000000 0x1234 0x1234 R E 0x10000
            va = next((p for p in parts if re.fullmatch(r"0x[0-9a-fA-F]+", p)), None)
            cands = [p for p in parts if re.fullmatch(r"0x[0-9a-fA-F]+", p)]
            if len(cands) >= 4:
                virt = cands[2]
                size = cands[4] if len(cands) > 4 else cands[-1]
                if re.fullmatch(r"0x[0-9a-fA-F]+", virt):
                    rom = virt
                try:
                    sz = int(size, 16)
                    rom_len = "0x20000" if sz <= 131072 else hex((sz + 0xFFFF) & ~0xFFFF)
                except Exception:
                    pass
            break
    return rom, rom_len


def cmd_cfg(args):
    batch = args.batch
    ids = list_firmware(batch, args.elfs)
    made = skipped = 0
    for fw in ids:
        elf = find_elf(batch, fw)
        if not elf:
            log(f"[跳过] {fw}: 没有 .elf")
            skipped += 1
            continue
        cfg_file = find_cfg(batch, fw)
        if cfg_file and not args.force:
            skipped += 1
            continue
        cfg_file = cfg_file or os.path.join(os.path.dirname(elf), f"{fw}.cfg")
        rom, rom_len = detect_mem(elf)
        with open(cfg_file, "w") as f:
            f.write(CFG_TEMPLATE.format(rom=rom, rom_len=rom_len, vtor=rom))
        made += 1
        log(f"[cfg] {fw}: rom={rom} len={rom_len}")
    log(f"cfg 完成: 生成 {made} 个, 跳过 {skipped} 个")


# ────────────────────────── init 迁移 ──────────────────────────

def cmd_init(args):
    src, batch = args.from_dir, args.batch
    src = os.path.abspath(src)
    if not os.path.isdir(src):
        sys.exit(f"源目录不存在: {src}")
    fw_root = firmware_dir(batch)
    os.makedirs(fw_root, exist_ok=True)

    n_fw = n_kb = 0
    for d in sorted(os.listdir(src)):
        if not d.isdigit():
            continue
        case = os.path.join(src, d)
        if not os.path.isdir(case):
            continue
        dst = os.path.join(fw_root, d)
        os.makedirs(dst, exist_ok=True)
        elf = glob.glob(os.path.join(case, "*.elf"))
        cfg = glob.glob(os.path.join(case, "*.cfg"))
        if elf:
            link = os.path.join(dst, os.path.basename(elf[0]))
            if not os.path.lexists(link):
                os.symlink(elf[0], link)
        if cfg:
            link = os.path.join(dst, os.path.basename(cfg[0]))
            if not os.path.lexists(link):
                os.symlink(cfg[0], link)
        n_fw += 1
        # KB：src/savedKB/*_KB.dat 或 src/*_KB.dat
        kbs = glob.glob(os.path.join(case, "savedKB", "*_KB.dat")) or \
              glob.glob(os.path.join(case, "*_KB.dat"))
        if kbs:
            kdst = kb_dir(batch, d)
            os.makedirs(kdst, exist_ok=True)
            for k in kbs:
                link = os.path.join(kdst, os.path.basename(k))
                if not os.path.lexists(link):
                    os.symlink(k, link)
            n_kb += 1
    log(f"init 完成: firmware/{batch} 软链 {n_fw} 个固件, kb/{batch} 软链 {n_kb} 个 KB")


# ────────────────────────── kb 阶段 ──────────────────────────

def run_kb_one(batch, fw, timeout, keep_raw, logfh):
    case = kb_dir(batch, fw)
    os.makedirs(case, exist_ok=True)
    elf = find_elf(batch, fw)
    cfg_file = find_cfg(batch, fw)
    if not elf or not cfg_file:
        return dict(fw=fw, kb_file="", status="缺少文件", elapsed_s=0)

    t0 = time.time()
    ok, made, err = gen_launch_scripts(elf, cfg_file, outdir=case)
    if not ok:
        log(f"[kb][配置失败] {fw}: {err}", logfh)
        return dict(fw=fw, kb_file="", status="配置失败", elapsed_s=round(time.time() - t0, 1))

    proc = None
    status = "未知"
    try:
        proc = subprocess.Popen(["bash", "launch-uEmu.sh"], cwd=case,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, start_new_session=True)
        proc.communicate(timeout=timeout)
        kb = find_kb(batch, fw) or ""
        status = "有KB" if kb else "无KB"
    except subprocess.TimeoutExpired:
        kill_tree(proc)
        status = "超时"
    except Exception as e:
        kill_tree(proc)
        status = f"异常({e})"

    elapsed = round(time.time() - t0, 1)
    # 先提取产物（KB 在 s2e-out-* 里），再清理 —— 顺序反了会把 KB 删掉
    finalize_case(case, keep_raw)
    kb = find_kb(batch, fw) or ""
    if status == "无KB" and kb:
        status = "有KB"          # 超时/异常但产物已落盘的，按实际产物记
    log(f"[kb][{status}] {fw} ({elapsed}s){' → ' + os.path.basename(kb) if kb else ''}", logfh)
    return dict(fw=fw, kb_file=os.path.basename(kb), status=status, elapsed_s=elapsed)


def extract_artifacts(case):
    """
    清理前把关键产物从 s2e-out-* 提到 case 根目录 —— 否则会被一起删掉。
      * _KB.dat        KB 提取的成果（fuzz 阶段要用）
      * fuzz_tb_map.txt 覆盖率的输入
    """
    got = []
    for d in sorted(glob.glob(os.path.join(case, "s2e-out-*"))):
        for pat in ("*_KB.dat", "fuzz_tb_map.txt"):
            for src in glob.glob(os.path.join(d, pat)):
                dst = os.path.join(case, os.path.basename(src))
                if not os.path.exists(dst):
                    shutil.copy2(src, dst)
                    got.append(os.path.basename(src))
    return got


def finalize_case(case, keep_raw):
    """提取关键产物 → 生成 debug_summary.txt → keep_raw=False 时删掉原始大文件。"""
    extract_artifacts(case)
    debug = None
    for d in glob.glob(os.path.join(case, "s2e-out-*")):
        c = os.path.join(d, "debug.txt")
        if os.path.isfile(c):
            debug = c
            break
    if debug:
        try:
            build_summary(debug, os.path.join(case, "uemu.log"),
                          os.path.join(case, "debug_summary.txt"))
        except Exception as e:
            log(f"  [警告] 摘要生成失败 {os.path.basename(case)}: {e}")
    if keep_raw:
        return
    for d in glob.glob(os.path.join(case, "s2e-out-*")):
        shutil.rmtree(d, ignore_errors=True)
    for f in ("uemu.log", "core"):
        p = os.path.join(case, f)
        if os.path.isfile(p):
            os.remove(p)
    link = os.path.join(case, "s2e-last")
    if os.path.islink(link):
        os.remove(link)


def cmd_kb(args):
    batch = args.batch
    ids = list_firmware(batch, args.elfs)
    timeout = args.timeout or CFG["timeout"]["kb"]
    jobs = args.jobs or CFG["parallel"]["kb"]
    logfh = open(log_path(batch, "kb.log"), "a")
    log(f"kb 阶段: {len(ids)} 个固件, 并发 {jobs}, 超时 {timeout}s", logfh)

    rows = []
    lock = threading.Lock()
    with ThreadPoolExecutor(max_workers=jobs) as ex:
        futs = [ex.submit(run_kb_one, batch, fw, timeout, args.keep_raw, logfh) for fw in ids]
        for i, f in enumerate(futs, 1):
            rows.append(f.result())
            if i % 10 == 0 or i == len(futs):
                log(f"  kb 进度 {i}/{len(futs)}", logfh)

    rows.sort(key=lambda r: int(r["fw"]))
    out = os.path.join(result_dir(batch), "kb_status.csv")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["fw", "kb_file", "status", "elapsed_s"])
        w.writeheader()
        w.writerows(rows)
    n_ok = sum(1 for r in rows if r["status"] == "有KB")
    log(f"kb 完成: 有KB {n_ok}/{len(rows)} → {os.path.relpath(out, ROOT)}", logfh)
    logfh.close()


# ────────────────────────── fuzz 阶段 ──────────────────────────

ETYPE_ZH = {"early": "提前退出", "clean": "跑满收尾", "killed": "跑满强杀"}


def run_fuzz_one(batch, fw, seed, timeout, keep_raw, logfh):
    case = fuzz_dir(batch, fw)
    os.makedirs(case, exist_ok=True)
    elf = find_elf(batch, fw)
    cfg_file = find_cfg(batch, fw)
    kb = find_kb(batch, fw)
    if not elf or not cfg_file:
        return dict(fw=fw, end_type="", duration_min=0, note="缺少 elf/cfg")

    # KB 文件必须与 cacheFileName 同名且同目录（helper 只写裸文件名）
    if kb:
        link = os.path.join(case, os.path.basename(kb))
        if not os.path.lexists(link):
            os.symlink(kb, link)

    ok, made, err = gen_launch_scripts(elf, cfg_file, kb_file=kb, seed=seed, outdir=case)
    if not ok:
        log(f"[fuzz][配置失败] {fw}: {err}", logfh)
        return dict(fw=fw, end_type="", duration_min=0, note=f"配置失败:{err[:60]}")

    patch_launch_afl(os.path.join(case, "launch-AFL.sh"), case, os.path.basename(elf))

    # 清掉上一轮的 AFL 目录
    shutil.rmtree(os.path.join(case, "AFL"), ignore_errors=True)

    afl_proc = uemu_proc = None
    afh = ufh = None
    t0 = time.time()
    end_type = "killed"
    try:
        afh = open(os.path.join(case, "afl.log"), "w")
        afl_proc = subprocess.Popen(["bash", "launch-AFL.sh"], cwd=case,
                                    stdout=afh, stderr=subprocess.STDOUT, start_new_session=True)
        time.sleep(CFG["fuzz"]["afl_startup_wait"])
        ufh = open(os.path.join(case, "uemu.log"), "w")
        uemu_proc = subprocess.Popen(["bash", "launch-uEmu.sh"], cwd=case,
                                     stdout=ufh, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            uemu_proc.wait(timeout=timeout)
            end_type = "early"                      # 超时前自行结束
        except subprocess.TimeoutExpired:
            kill_tree(afl_proc, use_sigint=True)    # 先停 AFL，让 S2E 收到关闭信号
            afl_proc = None
            try:
                uemu_proc.wait(timeout=CFG["fuzz"]["uemu_stop_wait"])
                end_type = "clean"                  # 响应了关闭信号
            except subprocess.TimeoutExpired:
                kill_tree(uemu_proc)
                end_type = "killed"                 # 卡死被强杀
    except Exception as e:
        kill_tree(afl_proc, use_sigint=True)
        kill_tree(uemu_proc)
        log(f"[fuzz][异常] {fw}: {e}", logfh)
        end_type = "killed"
    finally:
        for h in (afh, ufh):
            if h:
                h.close()
        if afl_proc and afl_proc.poll() is None:
            kill_tree(afl_proc, use_sigint=True)
        if uemu_proc and uemu_proc.poll() is None:
            kill_tree(uemu_proc)

    dur = round((time.time() - t0) / 60, 1)
    # 跑满却走 [uEmu结束] 分支的，实质是正常收尾
    if end_type == "early" and dur >= timeout / 60 - 0.5:
        end_type = "clean"

    finalize_case(case, keep_raw)
    with open(os.path.join(case, ".run.json"), "w") as f:
        json.dump(dict(fw=fw, started=datetime.now().isoformat(timespec="seconds"),
                       duration_min=dur, end_type=end_type), f)
    log(f"[fuzz][{ETYPE_ZH.get(end_type, end_type)}] {fw} ({dur}m)", logfh)
    return dict(fw=fw, end_type=end_type, duration_min=dur, note="")


def cmd_fuzz(args):
    batch = args.batch
    ids = list_firmware(batch, args.elfs)
    seed = os.path.abspath(args.seed)
    if not os.path.isfile(seed):
        sys.exit(f"种子文件不存在: {seed}")
    timeout = args.timeout or CFG["timeout"]["fuzz"]
    jobs = args.jobs or CFG["parallel"]["fuzz"]
    logfh = open(log_path(batch, "fuzz.log"), "a")
    log(f"fuzz 阶段: {len(ids)} 个固件, 并发 {jobs}, 超时 {timeout}s, 种子 {seed}", logfh)

    rdir = result_dir(batch)
    os.makedirs(rdir, exist_ok=True)
    with open(os.path.join(rdir, "manifest.json"), "w") as f:
        json.dump(dict(batch=batch, seed=seed, uEmuDIR=CFG["_uemu_dir"],
                       timeout=dict(kb=CFG["timeout"]["kb"], fuzz=timeout),
                       parallel=jobs, firmware_count=len(ids),
                       started=datetime.now().isoformat(timespec="seconds")), f,
                  ensure_ascii=False, indent=2)

    rows = []
    with ThreadPoolExecutor(max_workers=jobs) as ex:
        futs = [ex.submit(run_fuzz_one, batch, fw, seed, timeout, args.keep_raw, logfh) for fw in ids]
        for i, f in enumerate(futs, 1):
            rows.append(f.result())
            if i % 10 == 0 or i == len(futs):
                log(f"  fuzz 进度 {i}/{len(futs)}", logfh)

    with open(os.path.join(rdir, "fuzz_status.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["fw", "end_type", "duration_min", "note"])
        w.writeheader()
        w.writerows(sorted(rows, key=lambda r: int(r["fw"])))
    for et in ("early", "clean", "killed"):
        c = sum(1 for r in rows if r["end_type"] == et)
        log(f"  {ETYPE_ZH[et]}: {c}", logfh)
    logfh.close()


# ────────────────────────── analyze ──────────────────────────

def read_fuzzer_stats(case):
    hits = glob.glob(os.path.join(case, "AFL", "*_fuzz_out", "fuzzer_stats"))
    if not hits:
        return {}
    kv = {}
    for line in open(hits[0], errors="ignore"):
        if ":" in line:
            k, v = line.split(":", 1)
            kv[k.strip()] = v.strip()
    return kv


def count_queue(case):
    hits = glob.glob(os.path.join(case, "AFL", "*_fuzz_out", "queue"))
    if not hits:
        return 0
    return len([f for f in os.listdir(hits[0]) if not f.startswith(".")])


def si(d, k):
    try:
        return int(d.get(k, 0) or 0)
    except (TypeError, ValueError):
        return 0


# ── 运行记录回退：.run.json 缺失时从 fuzz-log 反推 end_type ──
_RUN_CACHE = {}

RE_LOG_START = re.compile(r"\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\] \[开始\] (\d+)")
RE_LOG_END = [
    (re.compile(r"\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\]\s+\[uEmu结束\] (\d+)"), "natural"),
    (re.compile(r"\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\]\s+\[uEmu自动停止\] (\d+)"), "auto"),
    (re.compile(r"\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\]\s+\[uEmu未自动停止，强制停止\] (\d+)"), "forced"),
]
TS_FMT = "%Y-%m-%d %H:%M:%S"


def load_run_records():
    """扫 fuzz-log，返回 {fw: [{end, dur_min, kind}]}。仅用于 .run.json 缺失时的回退。"""
    if _RUN_CACHE:
        return _RUN_CACHE
    pend = {}
    runs = {}
    try:
        import glob as _g
        for p in sorted(_g.glob(os.path.join(ROOT, "fuzz-log", "*.log"))):
            with open(p, errors="ignore") as f:
                for line in f:
                    m = RE_LOG_START.search(line)
                    if m:
                        pend.setdefault(m.group(2), []).append(
                            datetime.strptime(m.group(1), TS_FMT))
                        continue
                    for rx, kind in RE_LOG_END:
                        m = rx.search(line)
                        if not m:
                            continue
                        fw = m.group(2)
                        t = datetime.strptime(m.group(1), TS_FMT)
                        for i in range(len(pend.get(fw, [])) - 1, -1, -1):
                            d = (t - pend[fw][i]).total_seconds()
                            if 0 <= d <= 5 * 3600:
                                pend[fw].pop(i)
                                runs.setdefault(fw, []).append(
                                    dict(end=t, dur_min=d / 60, kind=kind))
                                break
                        break
    except Exception:
        pass
    _RUN_CACHE.update(runs)
    return _RUN_CACHE


def get_runinfo(fw, case):
    """.run.json 优先；缺失时回退到 fuzz-log。返回 {duration_min, end_type}。"""
    rp = os.path.join(case, ".run.json")
    if os.path.exists(rp):
        try:
            info = json.load(open(rp))
            if info:
                return info
        except Exception:
            pass
    return run_from_log(fw, case)


def run_from_log(fw, case):
    """.run.json 缺失时，用目录最新 mtime 匹配 fuzz-log 的运行记录。"""
    rs = load_run_records().get(str(fw))
    if not rs:
        return {}
    mts = []
    for r, _, fs in os.walk(case):
        for f in fs:
            if f.startswith(".") or "-cov.csv" in f:
                continue
            try:
                mts.append(os.path.getmtime(os.path.join(r, f)))
            except OSError:
                pass
    if not mts:
        return {}
    mt = datetime.fromtimestamp(max(mts))
    best = None
    for r in rs:
        dt = (r["end"] - mt).total_seconds()
        if -48 * 3600 <= dt <= 90 * 60 and (best is None or abs(dt) < abs(best[0])):
            best = (dt, r)
    if not best:
        return {}
    r = best[1]
    if r["kind"] == "natural":
        end_type = "early" if r["dur_min"] < 60 else "clean"
    elif r["kind"] == "auto":
        end_type = "clean"
    else:                                   # forced
        end_type = "killed"
    return dict(duration_min=round(r["dur_min"], 1), end_type=end_type)


def classify(batch, fw):
    """两级判定：result(3类) + details(细分)。返回 (result, details, evidence)。"""
    case = fuzz_dir(batch, fw)
    kv = read_summary(os.path.join(case, "debug_summary.txt"))
    stats = read_fuzzer_stats(case)
    queue = count_queue(case)
    runinfo = get_runinfo(fw, case)
    end_type = runinfo.get("end_type", "")

    afl_log = os.path.join(case, "afl.log")
    atxt = open(afl_log, errors="ignore").read() if os.path.exists(afl_log) else ""
    ab = re.search(r"PROGRAM ABORT\s*:?\s*(.*)", re.sub(r"\x1b\[[0-9;]*m", "", atxt))
    abr = ab.group(1).strip() if ab else ""

    # ===== result =====
    if queue > 1:
        result = "正常fuzz"
    elif end_type == "early":
        result = "提前退出"
    else:
        result = "卡死"

    # ===== details =====
    if result == "正常fuzz":
        uc = si(stats, "unique_crashes")
        if uc > 0:
            return result, f"正常fuzzing(发现{uc}个crash)", f"queue={queue} unique_crashes={uc}"
        return result, ("正常fuzzing" if kv else "正常fuzzing(仅AFL证据)"), f"queue={queue}"

    if kv is None:
        if ab:
            if "results in a crash" in abr:
                return result, "fuzzer:dry-run种子crash", abr[:80]
            if "results in a timeout" in abr:
                return result, "fuzzer:dry-run种子超时", abr[:80]
            return result, "fuzzer:dry-run中止(其他原因)", abr[:80]
        if "Killed afl-fuzz" in atxt and "Fuzzing test case" not in atxt:
            return result, "fuzzer:AFL被信号终止(SIGKILL)", ""
        return result, "证据缺失(无debug_summary)", ""

    kf, inv, sg, kp = si(kv, "KF"), si(kv, "INV"), si(kv, "SEGFAULT"), si(kv, "KP")
    kb, fk, np_ = si(kv, "KB"), si(kv, "FK"), si(kv, "NP")
    hg, dl, ll = si(kv, "HANG"), si(kv, "DL"), si(kv, "LL")
    lines = si(kv, "lines")
    kfl, kpl = kv.get("kf_line", ""), kv.get("kp_line", "")
    term = kv.get("termination", "")
    pf, ex = si(stats, "paths_found"), si(stats, "execs_done")
    try:
        eps = float(stats.get("execs_per_sec", 0) or 0)
    except ValueError:
        eps = 0.0
    ev = f"KB={kb} AR={si(kv,'AR')} NP={np_} FK={fk}"

    # fuzzer 侧
    if ab:
        if "results in a crash" in abr:   return result, "fuzzer:dry-run种子crash", abr[:80]
        if "results in a timeout" in abr: return result, "fuzzer:dry-run种子超时", abr[:80]
        return result, "fuzzer:dry-run中止(其他原因)", abr[:80]
    if "Killed afl-fuzz" in atxt and "Fuzzing test case" not in atxt:
        return result, "fuzzer:AFL被信号终止(SIGKILL)", ""
    # uEmu 崩溃
    if kf:
        if "out of bound" in kfl:       return result, "uEmu:崩溃-越界访问", kfl[:120]
        if "Timeout" in kfl:            return result, "uEmu:崩溃-超时", kfl[:120]
        if "Fault interrupt" in kfl:    return result, "uEmu:崩溃-HardFault", kfl[:120]
        if "writing read-only" in kfl:  return result, "uEmu:崩溃-写只读ROM", kfl[:120]
        return result, "uEmu:崩溃-fuzz-state-killed", kfl[:120]
    if inv: return result, "uEmu:崩溃-无效内存访问", ev
    if sg:  return result, "uEmu:崩溃-QEMU段错误", f"SEGFAULT={sg}"
    if kp:  return result, "uEmu:崩溃-invalid-pc", kpl[:120]
    # 起步
    if np_ == 0 and lines and lines < 1000:
        return result, "uEmu:启动即退出", f"lines={lines}"
    if np_ == 0:
        return result, "uEmu:未进入learning(未见任何外设)", f"lines={lines} HANG={hg}"
    # 震荡
    if pf == 0 and ex > 0 and np_ > 10 and abs(np_ - ex) <= max(8, np_ * 0.25):
        return result, "uEmu:learning↔fuzzing震荡(fuzzing零产出)", f"NP={np_} execs={ex} eps={eps}"
    # hang 主导
    if hg > 0 and kb <= 1 and fk == 0:
        return result, "uEmu:初始化hang", f"HANG={hg}"
    if kb == 0:
        return result, "uEmu:未完成KB提取", ev
    # 未命中输入点
    if fk == 0:
        if ll > 0: return result, "uEmu:未命中输入点(long-loop拖住)", f"LL={ll} {ev}"
        if dl > 0: return result, "uEmu:未命中输入点(dead-loop拖住)", f"DL={dl} {ev}"
        if hg > 0: return result, "uEmu:未命中输入点(hang拖住)", f"HANG={hg} {ev}"
        return result, "uEmu:未命中输入点", f"{kb}轮后 {ev}"
    # 命中输入点但未产出
    if "can not be switch" in term:
        return result, "uEmu:命中输入点但状态切换被拒", "Learning state can not be switch"
    if ll > 0: return result, "uEmu:命中输入点但被long-loop拖住", f"LL={ll} {ev}"
    if dl > 0: return result, "uEmu:命中输入点但被dead-loop拖住", f"DL={dl} {ev}"
    if hg > 0: return result, "uEmu:命中输入点但被hang拖住", f"HANG={hg} {ev}"
    return result, "uEmu:命中输入点但未产出", ev


def cmd_analyze(args):
    batch = args.batch
    ids = list_firmware(batch, args.elfs)
    logfh = open(log_path(batch, "analyze.log"), "a")
    rows, evs = [], []
    for fw in ids:
        r, d, ev = classify(batch, fw)
        runinfo = get_runinfo(fw, fuzz_dir(batch, fw))
        queue = count_queue(fuzz_dir(batch, fw))
        rows.append(dict(fw=fw, result=r, details=d,
                         duration_min=runinfo.get("duration_min", ""),
                         end_type=runinfo.get("end_type", ""), queue=queue))
        evs.append(dict(fw=fw, detail=d, evidence=ev))

    rdir = result_dir(batch)
    os.makedirs(rdir, exist_ok=True)
    with open(os.path.join(rdir, "results.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["fw", "result", "details",
                                          "duration_min", "end_type", "queue"])
        w.writeheader()
        w.writerows(rows)
    os.makedirs(os.path.join(rdir, "logs"), exist_ok=True)
    with open(os.path.join(rdir, "logs", "evidence.jsonl"), "w") as f:
        for e in evs:
            f.write(json.dumps(e, ensure_ascii=False) + "\n")

    # 终端汇总
    print()
    print("=" * 72)
    for r in ("正常fuzz", "提前退出", "卡死"):
        sub = [x for x in rows if x["result"] == r]
        print(f"{r}  ({len(sub)})")
        agg = {}
        for x in sub:
            agg[x["details"]] = agg.get(x["details"], 0) + 1
        for d, c in sorted(agg.items(), key=lambda x: -x[1]):
            print(f"    {d:<46} {c:>4}")
        print()
    print(f"合计 {len(rows)} 个固件")
    print(f"结果 → {os.path.relpath(os.path.join(rdir, 'results.csv'), ROOT)}")
    print(f"依据 → {os.path.relpath(os.path.join(rdir, 'logs', 'evidence.jsonl'), ROOT)}")
    logfh.close()


# ────────────────────────── coverage ──────────────────────────

def cmd_coverage(args):
    batch = args.batch
    ids = list_firmware(batch, args.elfs)
    calc = os.path.join(CFG["_uemu_dir"], "calculate.py")
    # bbl 是固件属性，默认放 firmware/<batch>/valid_block_range/，可用 --bbl-dir 覆盖
    bbl_dir = args.bbl_dir or os.path.join(firmware_dir(batch), "valid_block_range")
    rows = []
    for fw in ids:
        case = fuzz_dir(batch, fw)
        mp = os.path.join(case, "fuzz_tb_map.txt")
        bbl = os.path.join(bbl_dir, f"{fw}_bbl.txt")
        if not os.path.exists(mp):
            rows.append(dict(fw=fw, total_bbl="", covered="", pct="", note="无 fuzz_tb_map.txt"))
            continue
        if not os.path.exists(bbl):
            rows.append(dict(fw=fw, total_bbl="", covered="", pct="", note="无 bbl 文件"))
            continue
        try:
            # 中间文件隔离到 coverage/ 子目录：软链 map 进去，输出落在那里，
            # 不写进 fuzz 产物目录（避免软链批次误改原数据）
            cdir = os.path.join(result_dir(batch), "coverage")
            os.makedirs(cdir, exist_ok=True)
            link = os.path.join(cdir, f"{fw}_tb_map.txt")
            if not os.path.lexists(link):
                os.symlink(os.path.abspath(mp), link)
            cov = link + "-cov.csv"
            if not os.path.exists(cov):
                subprocess.run(["python3", calc, bbl, link], capture_output=True, timeout=600)
            with open(bbl) as f:
                total = sum(1 for _ in f)
            covered = 0
            if os.path.exists(cov):
                with open(cov) as f:
                    covered = len({ln.split()[0] for ln in f if ln.strip()})
            pct = round(covered / total * 100, 2) if total else 0.0
            rows.append(dict(fw=fw, total_bbl=total, covered=covered, pct=pct, note=""))
        except Exception as e:
            rows.append(dict(fw=fw, total_bbl="", covered="", pct="", note=str(e)[:60]))

    out = os.path.join(result_dir(batch), "coverage.csv")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["fw", "total_bbl", "covered", "pct", "note"])
        w.writeheader()
        w.writerows(rows)

    valid = [r for r in rows if isinstance(r["pct"], float) and r["pct"] > 0]
    log(f"coverage 完成: {len(valid)}/{len(rows)} 个有覆盖率 → {os.path.relpath(out, ROOT)}")
    if valid:
        avg = sum(r["pct"] for r in valid) / len(valid)
        log(f"  平均 {avg:.2f}%  最高 {max(r['pct'] for r in valid):.2f}%  "
            f"最低 {min(r['pct'] for r in valid):.2f}%")


# ────────────────────────── clean ──────────────────────────

def cmd_clean(args):
    batch = args.batch
    ids = list_firmware(batch, args.elfs)
    freed = 0
    for fw in ids:
        for case in (kb_dir(batch, fw), fuzz_dir(batch, fw)):
            if not os.path.isdir(case):
                continue
            for d in glob.glob(os.path.join(case, "s2e-out-*")):
                for r, _, fs in os.walk(d):
                    for fn in fs:
                        freed += os.path.getsize(os.path.join(r, fn))
                shutil.rmtree(d, ignore_errors=True)
            for fn in ("uemu.log", "core"):
                p = os.path.join(case, fn)
                if os.path.isfile(p):
                    freed += os.path.getsize(p)
                    os.remove(p)
            link = os.path.join(case, "s2e-last")
            if os.path.islink(link):
                os.remove(link)
    log(f"clean 完成: 释放 {freed / 2**30:.2f} GiB")


# ────────────────────────── CLI ──────────────────────────

def main():
    global CFG
    p = argparse.ArgumentParser(prog="pipeline.py", description="uEmu 固件 fuzz 流水线")
    sub = p.add_subparsers(dest="cmd", required=True)

    def common(sp, with_elfs=True, with_jobs=True):
        sp.add_argument("--batch", required=True, help="批次名（对应 firmware/<batch>/）")
        if with_elfs:
            sp.add_argument("--elfs", default="", help="只处理指定固件，逗号分隔，如 3389,3391")

    sp = sub.add_parser("init", help="从旧目录迁移（软链，不改动原数据）")
    sp.add_argument("--from", dest="from_dir", required=True, help="源目录，如 fuzz-test-1")
    common(sp, with_elfs=False)
    sp.set_defaults(func=cmd_init)

    sp = sub.add_parser("cfg", help="生成/校验 firmware/<batch>/*/*.cfg")
    common(sp)
    sp.add_argument("--force", action="store_true", help="覆盖已存在的 cfg")
    sp.set_defaults(func=cmd_cfg)

    sp = sub.add_parser("kb", help="KB 提取阶段 → result/<batch>/kb/")
    common(sp)
    sp.add_argument("--jobs", type=int, default=0)
    sp.add_argument("--timeout", type=int, default=0)
    sp.add_argument("--keep-raw", action="store_true",
                    help="保留 debug.txt/uemu.log 等原始文件（不清）")
    sp.set_defaults(func=cmd_kb)

    sp = sub.add_parser("fuzz", help="fuzz 阶段 → result/<batch>/fuzz/")
    common(sp)
    sp.add_argument("--seed", required=True, help="种子文件路径")
    sp.add_argument("--jobs", type=int, default=0)
    sp.add_argument("--timeout", type=int, default=0)
    sp.add_argument("--keep-raw", action="store_true")
    sp.set_defaults(func=cmd_fuzz)

    sp = sub.add_parser("analyze", help="分类 → result/<batch>/results.csv")
    common(sp)
    sp.set_defaults(func=cmd_analyze)

    sp = sub.add_parser("coverage", help="覆盖率 → result/<batch>/coverage.csv")
    common(sp)
    sp.add_argument("--bbl-dir", default="",
                    help="bbl 文件目录（默认 firmware/<batch>/valid_block_range）")
    sp.set_defaults(func=cmd_coverage)

    sp = sub.add_parser("clean", help="事后清理原始大文件（先生成摘要）")
    common(sp)
    sp.set_defaults(func=cmd_clean)

    args = p.parse_args()
    CFG = load_config()
    args.func(args)


if __name__ == "__main__":
    main()
