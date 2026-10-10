# OpenTenBase Stage 1 Report

Date: 2026-05-16

Target server: `root@47.108.249.115`

Scope: prepare an isolated OpenTenBase experiment environment, verify whether the official RPM can be installed safely on the current server, and run the first-stage single-node startup check for later SISM migration experiments.

## Summary

Stage 1 was partially completed.

- The server was confirmed reachable and stable for experimentation.
- No previous OpenTenBase package, service, or `ld.so` residue remained on the server before this run.
- An isolated experiment workspace was created under `/data/opentenbase`.
- The official `opentenbase-5.0-i.x86_64.rpm` package was downloaded locally, copied to the server, and installed successfully.
- Core binaries such as `psql`, `postgres`, `initdb`, and `pg_ctl` are runnable after installation.
- `opentenbase_ctl` is not currently runnable because the server lacks `libssh2.so.1`.
- A single-node coordinator data directory was initialized successfully.
- Startup failed at the memory stage: the instance attempted to allocate about `4304020096` bytes of shared memory on a server with about `1.8Gi` RAM.

Current judgment: this server is usable for package-level and compatibility experiments, but it is not suitable for a realistic OpenTenBase single-node runtime test in its current memory profile.

## Project Relevance

The current SISM backend is still a standard PostgreSQL-oriented Spring Boot stack:

- Spring Boot + Java 17 multi-module backend
- `Spring Data JPA`
- `Flyway`
- PostgreSQL JDBC driver
- database URL injected through `DB_URL`

This means the application-side migration entry remains concentrated at:

- JDBC connection target
- Flyway compatibility
- PostgreSQL SQL dialect compatibility in migrations and runtime queries

The current blocker is not in the SISM application layer yet. It is in the OpenTenBase runtime footprint on the target server.

## Server Baseline

Read-only inspection during this run showed:

- OS: `Alibaba Cloud Linux 3 (OpenAnolis Edition)`
- Root disk: `40G`, about `20G` available
- Memory: about `1.8Gi`
- Swap: `4.0Gi`
- SSH service: active

Before installation:

- no existing OpenTenBase RPM package was installed
- no `opentenbase` systemd unit was present
- no `/etc/ld.so.conf.d/*opentenbase*` residue was present
- no running OpenTenBase-related processes were present

## Actions Taken

### 1. Isolated experiment layout

Created:

- `/data/opentenbase`
- `/data/opentenbase/pkg`
- `/data/opentenbase/inspect`
- `/data/opentenbase/logs`
- `/data/opentenbase/tmp`

Created a dedicated OS user:

- `opentenbase`

### 2. RPM acquisition path

Server-side direct download still stalled on the GitHub release asset body.

Working path:

1. download RPM locally
2. copy RPM to the server with `scp`

Package used:

- `opentenbase-5.0-i.x86_64.rpm`

Observed package metadata:

- Name: `opentenbase`
- Version: `5.0`
- Architecture: `x86_64`
- Installed path: `/usr/local/install/opentenbase`

### 3. RPM safety inspection

The RPM post-install scriptlet only does:

- `chown -R root /usr/local/install/opentenbase`
- `chgrp -R root /usr/local/install/opentenbase`

It does not directly write `ld.so.conf`, does not run `ldconfig`, and does not register a systemd service during installation.

This materially reduced the SSH-breakage risk seen in the earlier failed experiment.

### 4. Binary verification

Verified successfully:

- `/usr/local/install/opentenbase/bin/psql --version`
- `/usr/local/install/opentenbase/bin/postgres -V`

Observed version string:

- `PostgreSQL 10.0 @ OpenTenBase_v5.0`

`ldd` showed that `psql` and `postgres` resolved against system libraries without requiring any global library-path override.

### 5. Controller verification

Failed:

- `/usr/local/install/opentenbase/bin/opentenbase_ctl --help`

Reason:

- missing shared library `libssh2.so.1`

This means the packaged orchestration tool is not usable yet on this host, even though core database binaries are available.

### 6. Single-node initialization test

Attempted a Stage 1 single-node bootstrap using the dedicated experiment user.

Key findings:

- `initdb` requires both `--nodename` and `--nodetype`
- initialization succeeded when using coordinator mode
- startup via `pg_ctl ... -Z coordinator` failed

First startup blocker:

- `max_wal_senders must be less than max_connections`

This was adjusted in `postgresql.conf` to force:

- `max_wal_senders = 5`
- `max_replication_slots = 5`
- `listen_addresses = localhost`

Second startup blocker after adjustment:

- OpenTenBase attempted to initialize about `4304020096` bytes of shared memory
- the process stopped before the server became ready

This is the effective runtime blocker for Stage 1 on this server.

## Evidence Highlights

Key runtime evidence captured during this run:

- `opentenbase_ctl: error while loading shared libraries: libssh2.so.1: cannot open shared object file`
- `pg_ctl: could not start server`
- `postgres: max_wal_senders must be less than max_connections`
- `Initializing shared memory and semaphores. Shared memory size: [4304020096]`

## Assessment

### What passed

- package transfer path
- RPM installation
- binary availability
- isolated data directory initialization
- no SSH/library-path regression introduced by this run

### What failed

- packaged cluster controller availability
- single-node runtime startup on the current low-memory server

### Root cause ranking

1. Server memory profile is too small for a practical OpenTenBase runtime experiment.
2. `opentenbase_ctl` has an unmet runtime dependency on `libssh2.so.1`.
3. Default initialization and runtime parameters are not tuned for a low-memory single-node lab host.

## Recommendation

### Immediate recommendation

Do not use this server as the formal OpenTenBase migration verification host for SISM.

Use it only for:

- package inspection
- dependency verification
- startup-parameter exploration

### Recommended next step

Provision a new test server with at least:

- `8Gi` RAM
- `4+` CPU cores
- `50Gi+` free disk

Then repeat Stage 1 there before touching SISM database connectivity.

### If continuing on the current host anyway

The next experiment order should be:

1. install the system package that provides `libssh2.so.1`
2. inspect whether `opentenbase_ctl` can then manage a single-node topology
3. aggressively tune low-memory parameters before startup
4. only if the database reaches a usable listening state, move to Flyway compatibility tests from SISM

## Suggested Stage 2 Entry Criteria

Do not start Stage 2 until all of the following are true:

- OpenTenBase listens on a TCP port successfully
- `psql` can create a test database and user
- the target database survives restart
- the server can run without memory thrashing or OOM behavior

Once those are true, Stage 2 should validate:

- `mvn -pl sism-main flyway:info`
- `mvn -pl sism-main flyway:migrate`
- Spring Boot startup against the OpenTenBase endpoint

## Files and Paths Created or Used

- `/data/opentenbase`
- `/data/opentenbase/pkg/opentenbase-5.0-i.x86_64.rpm`
- `/data/opentenbase/data/demo`
- `/data/opentenbase/logs/initdb-demo.log`
- `/data/opentenbase/logs/demo.log`
- `/usr/local/install/opentenbase`

## Final Conclusion

Stage 1 produced a usable answer quickly:

- the RPM path is workable
- the installation path is controllable
- the earlier SSH breakage risk was avoided in this run
- the current server is underpowered for meaningful OpenTenBase runtime validation

For SISM migration planning, the decisive next move is not code change. It is moving the experiment to a more suitable test host, then running Flyway and Spring Boot compatibility against that host.
