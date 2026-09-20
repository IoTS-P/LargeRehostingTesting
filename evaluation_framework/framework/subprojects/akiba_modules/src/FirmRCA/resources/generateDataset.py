# This script is a changed version of dataset.py from FirmRCA repository to help Akiba generate datasets

import os
import yaml
import shutil
import argparse

def dump_reverse_list(filename):
    instlist = []
    convert = {
        'r0(66)':'73',
        'r1(67)':'74',
        'r2(68)':'75',
        'r3(69)':'76',
        'r4(70)':'77',
        'r5(71)':'78',
        'r6(72)':'79',
        'r7(73)':'80',
        'r8(74)':'81',
        'r9(75)':'82',
        'r10(76)':'83',
        'r11(77)':'84',
        'r12(78)':'85',
        'PC(11)':'14',
        'LR(10)':'13',
        'SP(12)':'16',
    }
    with open(filename, 'r', encoding = 'utf8') as f:
        for line in f:
            if line.startswith('Hook at'):
                instlist.append(line.split()[2])
    with open('instlist.reverse', 'w', encoding = 'utf8') as f:
        for data in instlist[::-1]:
            print(data, file = f)

def generate_dataset(config_file, input_file):
    command_both_events = f'fuzzware_harness -c {config_file} {input_file} --trace-out=trace-out.txt --state-out=state-out.txt > crash-log.txt'
    instlist_path = os.path.join('instlist')
    traceout_path = os.path.join('trace-out.txt')

    os.system(command_both_events)

    dump_reverse_list(instlist_path)
    if os.path.exists(traceout_path):
        shutil.copy(traceout_path,'./memac.bin')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('firmrca_root', help='FirmRCA project root directory')
    parser.add_argument('config', help='Fuzzware config file')
    parser.add_argument('input', help='Fuzzware input file')
    args = parser.parse_args()

    os.chdir(args.firmrca_root)

    generate_dataset(args.config, args.input)

