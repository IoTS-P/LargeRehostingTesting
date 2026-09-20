**Note: Please do not clone this repository separately. Use the following command to clone the entire Akiba project:**

```shell
git clone https://github.com/IoTS-P/Akiba.git
cd Akiba
git submodule update --init --recursive
```

# Akiba Mod Utils

Akiba Mod Utils contains multiple classes and methods that encapsulate different parts of Ghidra, simplifying module development to a certain extent. Building will produce `amod-AkibaUtils-<version>.jar`. Place this JAR file into the `modules` directory of your module development project to import AkibaUtils for all modules to be developed.

The utils module includes the following aspects of class and function encapsulation:

- Advanced search and matching for Ghidra disassembly objects (assembly code, functions, P-code, memory, strings)
- Convenient operations for Ghidra ELF format
- Simple encapsulation of Ghidra emulator
- Encapsulation of Ghidra function-related operations
- Encapsulation of Ghidra high-level function analysis
- External process invocation (Python, etc.)

This module is expected to be continuously expanded to support more functionality.

## Build

You need to clone the `Akiba` main repository and pull all submodules, then build using Gradle:

```shell
./gradlew akiba_mod_utils:moduleJar-AkibaUtils
```

The built file will be located at `build/libs/amod-AkibaUtils-<version>.jar`

## Documentation

Akiba Mod Utils uses Dokka to generate documentation. Use the following command to generate HTML documentation in localhost (63342 in default):

```shell
./gradlew akiba_mod_utils:dokkaGenerateHtml
# http://localhost:63342/Akiba/subprojects/akiba_mod_utils/build/docs/html/
```

Note: Current documentation only contains Chinese.