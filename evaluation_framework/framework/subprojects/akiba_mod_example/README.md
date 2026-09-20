**Note: Please do not clone this repository separately. Use the following command to clone the entire Akiba project:**

```shell
git clone https://github.com/IoTS-P/Akiba.git
cd Akiba
git submodule update --init --recursive
```

# Akiba Example Module ( Akiba Mod Example )

The Akiba Example Module contains a simple module that retrieves all string values from a binary file and saves these values as a JSON array to a database.

You can develop based on this module by replacing the original functionality code in `AkibaExample.kt` and developing new features without significantly modifying other files such as `build.gradle.kts` used for building or project configuration.

This module depends on the `AkibaUtils` module. [Link](https://github.com/IoTS-P/Akiba-Mod-Utils). In that module's repository, you'll find the packaged `AkibaUtils` JAR file (located in modules).

## Build and Test Run

You need to clone the `Akiba` main repository and pull all submodules, then build using Gradle:

```shell
./gradlew akiba_mod_example:moduleJar-AkibaModExample
```

The built file will be `build/libs/amod-AkibaModExample-<version>.jar`

The built JAR file has been saved to the `dockerfile-needed` directory of the `Akiba` main repository. After starting the Docker service using the command below, run the test script to import an example binary file and start this example module:

```shell
# Run in the root directory of Akiba main repository
docker-compose up --build

# The following command runs in another terminal
docker exec -it akiba_app_1 /bin/bash
../binaries/test_run.sh
```

Under normal circumstances, the test script will first start Akiba Framework once to import the basic information of the example file into the local database sample, then start Akiba Framework again to run this example module. This example module will create a table named `example_table` in the database and write corresponding data. Finally, `test_run.sh` will run the `psql` command to check if the data exists. Correct data example is shown below:

```text
 id |              original_path              |             checksum             | size  |       arch        |               format                | compiler_spec 
----+-----------------------------------------+----------------------------------+-------+-------------------+-------------------------------------+---------------
  1 | /home/akiba/binaries/import_example.elf | 06beddb7382b86fa3a96c12607c98838 | 16536 | x86:LE:64:default | Executable and Linking Format (ELF) | unknown
(1 row)

 id |        start_timestamp        |       finish_timestamp        |  execute_time   | err_msg |                 strings                  
----+-------------------------------+-------------------------------+-----------------+---------+------------------------------------------
  1 | 2026-03-21 07:20:15.090135+00 | 2026-03-21 07:20:16.747139+00 | 00:00:01.657004 |         | ["please input", "ok,bye!!!", "/bin/sh"]
(1 row)
```

The first query result is used to check if the example binary file was successfully imported, and the second query result is used to check if this example module runs normally.