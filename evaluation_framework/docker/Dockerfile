###############################################################################
# LargeRehostingTesting — all-in-one rehosting pipeline image (akiba 3.1.2)
#
# Mirrors the container this pipeline was developed and evaluated in:
#   222.20.126.138, image akiba_allinone:3.1.2, user `akiba`, home /home/akiba,
#   db daemon on 31777, akiba instance on port 31777 / postgres 5432, ssh on 22.
#
# IMPORTANT — why the version is pinned:
#   akiba_allinone:4.x (the current upstream image) is NOT a drop-in replacement.
#   The framework layout, module API and entrypoint changed between the 3.1.x and
#   4.x lines, and every config in pipelines/ targets the 3.1.2 module set.
#   Keep ARG VERSION in sync with framework/build.gradle.kts.
###############################################################################
ARG UBUNTU_VERSION=24.04
ARG VERSION=3.1.2
ARG GHIDRA_VERSION=12.0.4
ARG GHIDRA_FIRMLINE_VERSION=11.3.2
ARG R2_VERSION=5.9.8

# ---------------------------------------------------------------------------
# Stage 1 — Ghidra releases + ghidra.jar
#
# akiba compiles and runs against a single "ghidra.jar" SDK jar that Ghidra's
# own support/buildGhidraJar produces from a full release.  The 3.1.2 line of
# the framework expects that jar at <subproject>/lib/ghidra.jar (see
# framework/build.gradle.kts) and at runtime in akiba_framework/lib/.
# Ghidra 11.3.2 is kept as a second installation because Firmline runs its own
# headless Ghidra analysis (config key `ghidraHome`).
# ---------------------------------------------------------------------------
FROM ubuntu:${UBUNTU_VERSION} AS ghidra
ARG GHIDRA_VERSION
ARG GHIDRA_FIRMLINE_VERSION
ENV DEBIAN_FRONTEND=noninteractive JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl unzip openjdk-21-jdk-headless \
 && rm -rf /var/lib/apt/lists/*
RUN set -eux; \
    mkdir -p /opt/ghidra; cd /opt/ghidra; \
    for v in "${GHIDRA_VERSION}" "${GHIDRA_FIRMLINE_VERSION}"; do \
      url="$(curl -fsSL "https://api.github.com/repos/NationalSecurityAgency/ghidra/releases/tags/Ghidra_${v}_build" \
             | grep -o 'https://[^"]*\.zip' | head -1)"; \
      test -n "$url"; \
      curl -fL "$url" -o "ghidra-${v}.zip"; \
      unzip -q "ghidra-${v}.zip"; \
      rm -f "ghidra-${v}.zip"; \
    done; \
    rm -rf /opt/ghidra/ghidra_*_PUBLIC/docs; \
    ls -d /opt/ghidra/ghidra_*_PUBLIC
RUN set -eux; mkdir -p /opt/ghidra-jar; cd /opt/ghidra-jar; \
    "/opt/ghidra/ghidra_${GHIDRA_VERSION}_PUBLIC/support/buildGhidraJar"; \
    chmod 644 /opt/ghidra-jar/ghidra.jar; \
    ls -la /opt/ghidra-jar

# ---------------------------------------------------------------------------
# Stage 1b — radare2, required by the Firmline module (it shells out to `r2`)
#
# Built from upstream at a pinned tag rather than from tools/firmline/radare2:
# that tree is only a bind mount (not part of the build context), and building it
# against a system capstone fails, because FirmRCA pins capstone 5.x while
# radare2's anal_arm_cs.c expects the older enum names (ARM64_INS_ADRP).
# radare2's own bundled capstone fork is used here instead.
# ---------------------------------------------------------------------------
FROM ubuntu:${UBUNTU_VERSION} AS radare2
ARG R2_VERSION
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl git build-essential pkg-config file xz-utils python3 \
 && rm -rf /var/lib/apt/lists/*
RUN set -eux; \
    git clone --depth 1 --branch "${R2_VERSION}" https://github.com/radareorg/radare2 /tmp/radare2; \
    cd /tmp/radare2; \
    ./sys/install.sh; \
    /usr/local/bin/r2 -v; \
    ls -la /usr/local/lib/libr_*.so | head -3

# ---------------------------------------------------------------------------
# Stage 2 — build the akiba framework, database daemon and every analysis module
# ---------------------------------------------------------------------------
FROM ubuntu:${UBUNTU_VERSION} AS akiba-build
ARG VERSION
ENV DEBIAN_FRONTEND=noninteractive JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl unzip openjdk-21-jdk python3 \
 && rm -rf /var/lib/apt/lists/*
# Gradle is installed from the release ZIP instead of the wrapper: the wrapper's
# download of the distribution fails on this network (connection reset from
# services.gradle.org), while curl retries fine.  Version matches
# gradle/wrapper/gradle-wrapper.properties (8.8).
RUN set -eux; \
    curl -fL --retry 5 --retry-delay 5 --retry-all-errors -o /tmp/gradle.zip \
      https://services.gradle.org/distributions/gradle-8.8-bin.zip; \
    unzip -q -d /opt /tmp/gradle.zip; rm -f /tmp/gradle.zip; \
    /opt/gradle-8.8/bin/gradle --version

COPY evaluation_framework/framework /build/framework
COPY --from=ghidra /opt/ghidra-jar/ghidra.jar /build/framework/lib/ghidra.jar
# every subproject resolves `files("lib/ghidra.jar")` relative to its own dir
RUN set -eux; cd /build/framework; \
    for p in akiba_framework akiba_db_daemon akiba_modules akiba_mod_utils akiba_mod_example; do \
      mkdir -p "subprojects/$p/lib"; \
      ln -sf /build/framework/lib/ghidra.jar "subprojects/$p/lib/ghidra.jar"; \
    done

COPY evaluation_framework/scripts/build_akiba_modules.py /build/build_akiba_modules.py
# The module build resolves inter-module dependencies to build/libs/amod-*.jar while
# creating each task, so it cannot build a clean tree in one Gradle invocation:
# scripts/build_akiba_modules.py parses the dependency graph out of build.gradle.kts and
# builds the modules in topological batches.
RUN set -eux; cd /build/framework; chmod +x gradlew; \
    GRADLE=/opt/gradle-8.8/bin/gradle; \
    $GRADLE --no-daemon --console=plain ":akiba_framework:distZip" ":akiba_db_daemon:distZip" ":akiba_mod_utils:moduleJar-AkibaUtils"; \
    chmod +x /build/build_akiba_modules.py; \
    python3 /build/build_akiba_modules.py \
        --project /build/framework/subprojects/akiba_modules \
        --gradle-root /build/framework --gradle $GRADLE --jobs 4 \
        --log /tmp/akiba_modules_build.log

RUN set -eux; cd /build/framework; mkdir -p /out; \
    unzip -q "subprojects/akiba_framework/build/distributions/akiba_framework-${VERSION}.zip" -d /out; \
    mv "/out/akiba_framework-${VERSION}" /out/akiba_framework; \
    unzip -q "subprojects/akiba_db_daemon/build/distributions/akiba_db_daemon-${VERSION}.zip" -d /out; \
    mv "/out/akiba_db_daemon-${VERSION}" /out/akiba_db_daemon; \
    mkdir -p /out/akiba_framework/modules; \
    find subprojects/akiba_modules/build/libs -name 'amod-*.jar' -exec cp {} /out/akiba_framework/modules/ \;; \
    find subprojects/akiba_mod_utils/build/libs -name 'amod-*.jar' -exec cp {} /out/akiba_framework/modules/ \;; \
    cp /build/framework/lib/ghidra.jar /out/akiba_framework/lib/ghidra.jar; \
    chmod +x /out/akiba_framework/bin/* /out/akiba_db_daemon/bin/*; \
    echo "modules built: $(ls /out/akiba_framework/modules | wc -l)"; \
    ls /out/akiba_framework/modules | head

# ---------------------------------------------------------------------------
# Stage 3 — runtime image
# ---------------------------------------------------------------------------
FROM ubuntu:${UBUNTU_VERSION} AS final
ARG VERSION
ARG GHIDRA_VERSION
ARG GHIDRA_FIRMLINE_VERSION
ARG PROVISION_TOOLS=0

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
    CONDA_DIR=/opt/conda \
    GHIDRA_HOME=/opt/ghidra/ghidra_${GHIDRA_VERSION}_PUBLIC \
    GHIDRA_FIRMLINE_HOME=/opt/ghidra/ghidra_${GHIDRA_FIRMLINE_VERSION}_PUBLIC \
    PATH=/opt/conda/bin:/home/akiba/.cargo/bin:/home/akiba/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# OS packages: JDK21 + postgres + pgbackrest come from the 3.1.2 Dockerfile; the
# rest are the toolchains the six evaluated tools need (build-base, rust/cargo,
# cmake, autotools, python, lua-free zsh + virtualenvwrapper for the fuzzware
# gateway modules, openssh-server so the container can be driven like the server).
RUN apt-get update && apt-get install -y --no-install-recommends \
      openjdk-21-jdk-headless postgresql-16 postgresql-contrib pgbackrest \
      sudo passwd login \
      curl wget git unzip zip tar xz-utils file less procps psmisc nano vim tmux \
      ca-certificates gnupg rsync netcat-openbsd iproute2 jq \
      openssh-server \
      zsh virtualenv virtualenvwrapper python3 python3-pip python3-venv python3-dev \
      sqlite3 libsqlite3-dev \
      build-essential automake autoconf libtool pkg-config cmake clang llvm lld patchelf \
      libffi-dev libssl-dev zlib1g-dev libglib2.0-dev libpixman-1-dev libcapstone-dev \
      bison flex gettext texinfo gperf ninja-build protobuf-compiler \
      gcc-arm-none-eabi binutils-arm-none-eabi libnewlib-arm-none-eabi \
      redis-server \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# --- Miniconda: the fuzzware and firmline virtualenvs need python 3.10 / 3.11,
#     which Ubuntu 24.04 does not ship.  /usr/bin/python3.10 is symlinked so that
#     fuzzware's install_local.sh (`mkvirtualenv -p /usr/bin/python3.10`) works.
ARG MINICONDA_URL=https://repo.anaconda.com/miniconda/Miniconda3-latest-Linux-x86_64.sh
RUN set -eux; \
    curl -fsSL "$MINICONDA_URL" -o /tmp/miniconda.sh; \
    bash /tmp/miniconda.sh -b -p /opt/conda; rm -f /tmp/miniconda.sh; \
    /opt/conda/bin/conda create -y -n py310 --override-channels -c conda-forge python=3.10 pip setuptools; \
    /opt/conda/bin/conda create -y -n py38 --override-channels -c conda-forge python=3.8 pip setuptools; \
    /opt/conda/bin/conda create -y -n firmline --override-channels -c conda-forge python=3.11 pip setuptools; \
    /opt/conda/bin/conda clean -afy; \
    ln -sf /opt/conda/envs/py310/bin/python3.10 /usr/bin/python3.10; \
    ln -sf /opt/conda/envs/py38/bin/python3.8 /usr/bin/python3.8; \
    ln -sf /opt/conda/envs/firmline/bin/python3 /usr/bin/python3.11; \
    /usr/bin/python3.10 --version && /usr/bin/python3.8 --version && /usr/bin/python3.11 --version

# --- virtualenvwrapper: the fuzzware/gdma install_local.sh scripts call
#     `which virtualenvwrapper.sh` and abort with "It requires an install." otherwise.
#     `which` checks the execute bit, and the packaged script (/usr/share/…) is 0644, so
#     a symlink is not enough — install an executable copy on PATH.  The python module has
#     to be importable by the same interpreter the wrapper will use (`which python3`).
RUN set -eux; \
    install -m 755 /usr/share/virtualenvwrapper/virtualenvwrapper.sh /usr/local/bin/virtualenvwrapper.sh; \
    python3 -m pip install --no-cache-dir virtualenvwrapper; \
    which virtualenvwrapper.sh; \
    python3 -c 'import virtualenvwrapper; print("virtualenvwrapper module ok")'

# --- user akiba (UID 1000, like the reference server: the host's bind-mounted tool
#     directories are owned by UID 1000, so the container user must match); the base
#     image already has a UID 1000 user (`ubuntu`), which is removed first
RUN set -eux; \
    if id -u 1000 >/dev/null 2>&1; then \
      existing="$(id -nu 1000)"; \
      if [ "$existing" != "akiba" ]; then userdel -r "$existing" 2>/dev/null || userdel "$existing" || true; fi; \
    fi; \
    useradd -m -u 1000 -s /bin/bash akiba; \
    usermod -aG sudo akiba; \
    echo "akiba ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/akiba; \
    chmod 440 /etc/sudoers.d/akiba; \
    mkdir -p /akiba /akiba/backups /akiba/instances /data/akiba/binaries \
             /data/samples /data/results /home/akiba/binaries /home/akiba/ghidra_projects; \
    chown -R akiba:akiba /akiba /data /home/akiba

# --- rust toolchain for hoedur / MultiFuzz (rustup, latest stable)
RUN set -eux; \
    su - akiba -c 'curl --proto "=https" --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable --profile minimal'; \
    su - akiba -c 'cargo --version'

# --- virtualenvwrapper for the fuzzware gateway modules (cmdPredo sources ~/.zshrc)
RUN set -eux; \
    printf '%s\n' \
      'export WORKON_HOME=/home/akiba/.virtualenvs' \
      'export VIRTUALENVWRAPPER_PYTHON=/usr/bin/python3' \
      'export PATH=$HOME/.cargo/bin:$PATH' \
      'source /usr/share/virtualenvwrapper/virtualenvwrapper.sh >/dev/null 2>&1' \
      '[ -f /opt/conda/etc/profile.d/conda.sh ] && source /opt/conda/etc/profile.d/conda.sh' \
      >> /home/akiba/.zshrc; \
    printf '%s\n' \
      'export WORKON_HOME=/home/akiba/.virtualenvs' \
      'export VIRTUALENVWRAPPER_PYTHON=/usr/bin/python3' \
      'export PATH=$HOME/.cargo/bin:$PATH' \
      'source /usr/share/virtualenvwrapper/virtualenvwrapper.sh >/dev/null 2>&1' \
      >> /home/akiba/.bashrc; \
    chown akiba:akiba /home/akiba/.zshrc /home/akiba/.bashrc

# --- Ghidra installations (runtime: ghidra.jar from the framework lib, plus the
#     two full installs used by Firmline / dynamic modules)
COPY --from=ghidra /opt/ghidra /opt/ghidra
COPY --from=ghidra /opt/ghidra-jar/ghidra.jar /opt/ghidra-jar/ghidra.jar
# the akiba user has to be able to read it: the tools (FirmXRay's javac call, the
# framework) link against it from a non-root account
RUN chmod 644 /opt/ghidra-jar/ghidra.jar

# --- radare2 (Firmline's static-analysis backend)
COPY --from=radare2 /usr/local/ /usr/local/

# --- akiba framework / db daemon / modules built in stage 2
COPY --from=akiba-build /out/akiba_framework /home/akiba/akiba_framework
COPY --from=akiba-build /out/akiba_db_daemon /home/akiba/akiba_db_daemon

COPY evaluation_framework/docker/entrypoint.sh /home/akiba/binaries/entrypoint.sh
COPY evaluation_framework/framework/dockerfile_needed/config_example.json   /home/akiba/binaries/config_example.json
COPY evaluation_framework/framework/dockerfile_needed/config_run_example.json /home/akiba/binaries/config_run_example.json
COPY evaluation_framework/framework/dockerfile_needed/import_example.json   /home/akiba/binaries/import_example.json
COPY evaluation_framework/framework/dockerfile_needed/import_example.elf    /home/akiba/binaries/import_example.elf
COPY evaluation_framework/framework/dockerfile_needed/test_run.sh           /home/akiba/binaries/test_run.sh
COPY evaluation_framework/framework/dockerfile_needed/amod-AkibaUtils-1.0.jar /home/akiba/binaries/amod-AkibaUtils-1.0.jar
COPY evaluation_framework/framework/dockerfile_needed/amod-AkibaExample-1.0.jar /home/akiba/binaries/amod-AkibaExample-1.0.jar
# the daemon's instance map lives in the persisted /akiba volume (see the note in
# docker/akiba_db_daemon_config.json); the shipped default would put it in ~/.akiba
COPY evaluation_framework/docker/akiba_db_daemon_config.json /home/akiba/akiba_db_daemon/resources/config.json
COPY evaluation_framework/docker/motd.txt /etc/motd

RUN set -eux; \
    chmod +x /home/akiba/binaries/entrypoint.sh /home/akiba/binaries/test_run.sh; \
    chown -R akiba:akiba /home/akiba; \
    mkdir -p /run/sshd; \
    sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/; s/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config; \
    echo 'akiba:akiba' | chpasswd; \
    su - akiba -c '/home/akiba/akiba_framework/bin/akiba_framework --help' || true

# optional: build the six evaluated tools into the image (long, see docs/README)
COPY evaluation_framework/scripts/ /opt/rehosting/scripts/
RUN if [ "$PROVISION_TOOLS" = "1" ]; then bash /opt/rehosting/scripts/setup_tools.sh; fi

USER akiba
WORKDIR /home/akiba/akiba_db_daemon
EXPOSE 22 31777
ENTRYPOINT ["/home/akiba/binaries/entrypoint.sh"]
CMD ["./bin/akiba_db_daemon", "-c", "./resources/config.json"]
