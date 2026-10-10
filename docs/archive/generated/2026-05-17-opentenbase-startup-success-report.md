# OpenTenBase Startup Success Report

Date: 2026-05-17

Scope: complete the highest-priority goal of bringing the OpenTenBase database
itself to a normal startup state on `47.108.249.115`.

## Result

Succeeded.

The database cluster reached a usable single-node running state with:

- GTM running
- coordinator running
- datanode running

Validated listeners:

- `6666` for GTM
- `30004` for coordinator
- `20008` for datanode
- `30024` for coordinator forward traffic
- `20028` for datanode forward traffic

## Validated processes

Observed running processes:

- `/data/opentenbase/install/opentenbase_bin_v5.0/bin/gtm -D /data/opentenbase/data/gtm`
- `/data/opentenbase/install/opentenbase_bin_v5.0/bin/postgres --coordinator -D /data/opentenbase/data/coord_master/cn001 -p 30004`
- `/data/opentenbase/install/opentenbase_bin_v5.0/bin/postgres --datanode -D /data/opentenbase/data/dn_master/dn001 -p 20008`

## Validated SQL checks

Using the coordinator:

- `select version();`
- `select current_database(), current_user;`
- `select 1 as smoke;`
- `select node_name, node_type, node_host, node_port from pgxc_node order by node_name;`

Validated node inventory:

- `cn001` / type `C` / `localhost:30004`
- `dn001` / type `D` / `localhost:20008`
- `gtm` / type `G` / `localhost:6666`

## What made the difference

### 1. Switched from RPM runtime path to source-built OpenTenBase 5.0

The RPM route repeatedly failed on GTM startup and runtime linkage behavior.

The working path used:

- source branch `v5.0-release_new`
- install target `/data/opentenbase/install/opentenbase_bin_v5.0`

### 2. Applied required compile flags

Validated compile flags:

- `-msse4.2 -mcrc32 -DNOLIC`

### 3. Patched GTM affinity failure

The decisive fix was changing GTM thread-affinity failure from a fatal error to
a warning.

Original symptom:

- `binding threads failed for 22`

Validated behavior after patch:

- warning is emitted
- GTM continues startup
- GTM stays alive long enough for CN/DN to start

### 4. Preserved low-memory CN/DN configs

The source build still required reduced CN/DN runtime settings on the small
host. The key low-memory values remained necessary.

### 5. Unified shell command resolution to the new build

`pgxc_ctl` single-node mode depends on bare command names and localhost SSH.

For successful startup:

- helper binaries had to resolve to the new build
- localhost SSH for `opentenbase` had to work non-interactively

## Remaining caveats

1. The running cluster is still license-bypassed through the documented
   `DNOLIC` build path.
2. The tested server is still resource-constrained.
3. The source build currently reports PostgreSQL version `10.0` in the client
   banner unless the optional version-string patch is also applied.

These caveats do not block database startup itself, but they should remain part
of the operator notes.

## Operational conclusion

The database startup objective is complete.

Further work, if needed, should now move to:

- banner/version compatibility cleanup
- Flyway and SISM backend validation against `localhost:30004`
- packaging the successful source-build recipe into a durable runbook
