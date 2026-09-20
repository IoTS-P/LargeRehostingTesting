# StringAdder

## Category

- General

## Description

Finds strings that have not been defined by Ghidra.

## Supplementary Explanation

Uses Ghidra's string search API to search for all ASCII character sequences. By default, we search for:
- Strings longer than 5 characters, ending with `'\0'`
- Strings containing UNIX control characters, such as `'\x1b[0;31m'`

Sometimes there are some strings shorter than 5 characters that cannot be recognized by Ghidra. We will explore the gaps between two strings and attempt to identify smaller strings.
