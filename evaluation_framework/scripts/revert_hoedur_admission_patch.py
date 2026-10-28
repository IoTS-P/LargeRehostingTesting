#!/usr/bin/env python3
"""Remove the mandatory-admission patch from a hoedur source tree.

The patched runner in the artifact's hoedur submodule runs a "Mandatory Admission Test" and then returns
from the runner, so `hoedur-arm fuzz` exits right after printing "Proceeding to Session 0" and never
fuzzes.  The admission test itself is what HoedurAdmissionTest needs, so the patched tree stays as it is;
the fuzzing stage uses a second tree without this block, built by setup_tools.sh.

Usage: revert_hoedur_admission_patch.py <path to hoedur/src/runner.rs>
"""
import pathlib
import re
import sys

START = ('    let mut coverage: Option<emulator::CoverageLog> = Some(Default::default());\n'
         '\n'
         '    {\n'
         '        use modeling::input::AdmissionMode;')
END = re.compile(r'        emulator\.snapshot_restore\(&dry_run_snapshot\);\s*\n'
                 r'        return Ok\(\(\)\);\s*\n    }\n')
KEEP = '    let mut coverage: Option<emulator::CoverageLog> = Some(Default::default());\n\n'


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: revert_hoedur_admission_patch.py <path to hoedur/src/runner.rs>")
        return 2
    path = pathlib.Path(sys.argv[1])
    source = path.read_text()

    if 'AdmissionMode' not in source:
        print(f'{path}: already clean (no admission block)')
        return 0

    start = source.find(START)
    if start < 0:
        print(f'{path}: admission block start not found', file=sys.stderr)
        return 1
    match = END.search(source, start)
    if not match:
        print(f'{path}: admission block end not found', file=sys.stderr)
        return 1

    removed = source[start:match.end()]
    path.write_text(source[:start] + KEEP + source[match.end():])
    print(f'{path}: removed {removed.count(chr(10))} lines of the admission block')
    return 0


if __name__ == '__main__':
    sys.exit(main())
