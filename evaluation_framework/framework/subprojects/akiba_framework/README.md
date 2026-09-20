**Note: Please do not clone this repository separately. Use the following command to clone the entire Akiba project:**

```shell
git clone https://github.com/IoTS-P/Akiba.git
cd Akiba
git submodule update --init --recursive
```

# Akiba Runtime Framework

The Akiba Runtime Framework is the runtime entry point for the Akiba tool.

## How to Run

### Ghidra Preprocessing

1. Download Ghidra 11.3.2 from here: [Link](https://github.com/NationalSecurityAgency/ghidra/releases/download/Ghidra_11.3.2_build/ghidra_11.3.2_PUBLIC_20250826.zip)
2. Extract Ghidra.zip
3. (Optional but may be required by some modules) Extract some Ghidra official extensions (zip files) to `/Ghidra/Features`, so that Ghidra can package these extensions into `ghidra.jar` when building the JAR (by default, the `MachineLearning` extension is packaged)
4. Go to the `/support` directory and run the `buildGhidraJar` script file (run `buildGhidraJar.bat` on Windows)
5. If everything goes well, you can find the `ghidra.jar` file in `/support`. This is one of the dependencies Akiba needs. Copy it to `/path/to/Akiba/lib`

### Build

Build the Akiba Database Daemon, Akiba Framework, and Akiba Modules with a single command:

```shell
# Run from the Akiba root directory
./gradlew assemble  # On Windows, use gradlew.bat
```

### Run

```shell
cd build/distributions
unzip Akiba-<version>.zip
cd Akiba-<version>

# Copy the modules you need to use into the modules directory and write configuration files (Important!)

./bin/Akiba  # In Windows, use .\bin\Akiba.bat
```

## Command Line Options

### Basic Options

- `-c`/`--main-config <path>`: Set the main configuration file path. Format is `<file path>@<JSON path>`, e.g., `configs/config.json@/main`. Default value is `configs/config.yaml@main`
- `--venv <dir>`: Set the global Python virtual environment root directory, default is `akiba-venv`
- `-h`/`--help`: Show help message and exit
- `-V`/`--version`: Output version number and exit

### Runtime Mode Options (Mutually Exclusive, Choose One)

Akiba supports three runtime modes, which are mutually exclusive. Only one mode can be selected per run:

#### 1. Normal Analysis Mode (Default)

When no mode option is specified, Akiba will analyze the binary files in the database according to the task configuration in the main configuration file.

#### 2. Import Mode

- `-i`/`--import <config path>`: Import task mode. When this option is specified, Akiba will only perform file import tasks without any analysis work. An import configuration file path must be provided.

#### 3. Restore Mode

- `-r`/`--restore <'latest'/timestamp>`: Restore mode. When a task is interrupted due to bugs or other reasons, this mode can be used to continue the unfinished task.
  - The parameter can be `latest` (continue the latest task) or a timestamp (e.g., `20250201140000`)
  - **Note**: After specifying `-r`, the `-c` option will be ignored. Akiba will use the original configuration of the interrupted task (this configuration is copied to the `log` directory when the task first runs)
  - **Warning**: Unless you know exactly what you're doing, do not modify the configuration files in the `log` directory

Restore mode also supports the following additional options (used with `-r`, mutually exclusive):

- `-f`/`--fail-only`: Only reprocess programs marked as failed
- `-e`/`--error-only`: Only reprocess programs marked with errors

### Subcommands

Akiba also provides the following subcommands for managing database instances:

#### 1. instance-create - Create a New Database Instance

```shell
./bin/Akiba instance-create -n <name> -u <user> [other options]
```

**Required Parameters:**
- `-n`/`--name <name>`: Instance name
- `-u`/`--user <username>`: Akiba database username

**Optional Parameters:**
- `-P`/`--password`: Akiba user password. If not specified, will be prompted interactively
- `-H`/`--host <host>`: Database daemon host address, default is `127.0.0.1`
- `-p`/`--port <port>`: Database daemon port, default is `31777`
- `-h`/`--help`: Show help message and exit

**Examples:**
```shell
# Create an instance named test_instance with user admin, using default host and port
./bin/Akiba instance-create -n test_instance -u admin

# Specify host and port
./bin/Akiba instance-create -n my_instance -u admin -H 192.168.1.100 -p 31777
```

#### 2. instance-backup - Backup a Database Instance

```shell
./bin/Akiba instance-backup -i <instance> -t <type> -u <user> [other options]
```

**Required Parameters:**
- `-i`/`--instance <instance>`: Name of the instance to backup
- `-t`/`--type <type>`: Backup type, must be `full` (full backup) or `incr` (incremental backup)
- `-u`/`--user <username>`: Akiba database username

**Optional Parameters:**
- `-a`/`--alias <alias>`: Alias of the backup (for referencing in configuration files)
- `-d`/`--description <description>`: Description of the backup
- `-P`/`--password`: Akiba user password. If not specified, will be prompted interactively
- `-H`/`--host <host>`: Database daemon host address, default is `127.0.0.1`
- `-p`/`--port <port>`: Database daemon port, default is `31777`
- `-h`/`--help`: Show help message and exit

**Examples:**
```shell
# Perform a full backup on test_instance with alias backup_2025
./bin/Akiba instance-backup -i test_instance -t full -u admin -a backup_2025

# Perform an incremental backup on my_instance with description
./bin/Akiba instance-backup -i my_instance -t incr -u admin-d "Daily incremental backup"
```

#### 3. instance-restore - Restore a Database Instance from Backup

```shell
./bin/Akiba instance-restore -n <name> -l <label> -u <user> [other options]
```

**Required Parameters:**
- `-n`/`--name <name>`: Name of the restoration target instance to create
- `-l`/`--label <label>`: Label or alias of the backup (from instance-backup's `-a` parameter)
- `-u`/`--user <username>`: Akiba database username

**Optional Parameters:**
- `-P`/`--password`: Akiba user password. If not specified, will be prompted interactively
- `-H`/`--host <host>`: Database daemon host address, default is `127.0.0.1`
- `-p`/`--port <port>`: Database daemon port, default is `31777`
- `-h`/`--help`: Show help message and exit

**Examples:**
```shell
# Restore from backup_2025 to a new instance restored_instance
./bin/Akiba instance-restore -n restored_instance -l backup_2025 -u admin

# Restore from a specific host
./bin/Akiba instance-restore -n new_instance -l daily_backup -u admin -H 192.168.1.100
```

Other configuration files need to be saved in `src/main/resources/configs`. For details, see [Usage_guide_en.md](Usage_guide_en.md)
