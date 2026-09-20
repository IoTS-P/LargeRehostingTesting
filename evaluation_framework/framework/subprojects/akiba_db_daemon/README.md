**Note: Please do not clone this repository separately. Use the following command to clone the entire Akiba project:**

```shell
git clone https://github.com/IoTS-P/Akiba.git
cd Akiba
git submodule update --init --recursive
```

# Akiba Database Daemon

The Akiba Database Daemon is a lightweight web service that can build multiple PostgreSQL database instances locally and interact with the Akiba framework for data management.

Considering that Akiba may need to process a large number of binary files and may generate massive amounts of data during analysis, a robust data management system is essential.

Akiba uses PostgreSQL for data storage, supports multiple database instances, and uses pgbackrest for database backup and restoration. Akiba interacts with the outside world through HTTP interfaces and WebSocket interfaces.

You can build the Docker image for the Akiba Database Daemon using the following command:

```shell
docker build -t akiba-db-daemon .
```

The default port for the Database Daemon's web interface is 31777. Currently, it supports the following types of operations. For detailed parameter descriptions, see [Usage Guide](Usage_guide_en.md):

- User authentication (feature not yet complete, no password verification currently)
- File data insertion
- Task module management and data insertion, query, update
- Database instance management
- Database backup and restoration (related tests are not yet complete, may contain bugs, use with caution)
