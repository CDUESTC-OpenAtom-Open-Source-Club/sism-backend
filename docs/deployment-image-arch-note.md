# Deployment Image Architecture Note

This note records the production replacement lessons from the
`175.24.139.148` deployment on 2026-05-27.

## Architecture Rule

When building images from Apple Silicon or any non-amd64 workstation for the
production server `175.24.139.148`, always force:

```bash
docker buildx build --platform linux/amd64 ...
```

The production host is `linux/amd64`. Building a default local image on an
Apple Silicon Mac produces `linux/arm64`, which fails on the server with:

```text
exec format error
```

Before exporting or deploying images, verify:

```bash
docker image inspect <image>:<tag> --format '{{.Architecture}}/{{.Os}}'
```

Expected output for this server:

```text
amd64/linux
```

## What Failed

The first manual deployment exported images built with Docker defaults on an
Apple Silicon workstation. Those images were `linux/arm64`, while the server is
`linux/amd64`. The backend container started and immediately failed with:

```text
exec /app/backend-entrypoint.sh: exec format error
```

The failed stack was removed with `docker compose down -v`, the old systemd
backend was temporarily restarted, and nginx was not switched until the new
amd64 stack passed health checks.

## Faster Recovery Path

The normal Dockerfile includes a Maven `dependency:go-offline` layer. On slow or
unstable networks this can take a long time or fail on large dependency
downloads. When a local Maven build is already reliable, this recovery path is
acceptable for manual emergency deployment:

1. Build the current backend jar locally:

```bash
mvn -B -pl sism-main -am package -Dmaven.test.skip=true -Dmaven.javadoc.skip=true
```

2. Extract Spring Boot layers from the generated jar:

```bash
java -Djarmode=layertools \
  -jar sism-main/target/sism-main-1.0.0.jar \
  extract --destination /tmp/sism-backend-runtime-amd64/layers
```

3. Build a runtime-only image with `--platform linux/amd64`, using the same
   `docker/backend-entrypoint.sh` and the extracted layers.

This avoids compiling on the low-memory production server and avoids the slow
Docker Maven dependency layer. It should be treated as a manual fallback, not as
the default CI path.

## Verification Checklist

Before uploading image tar files to the server:

```bash
docker image inspect sism-backend:<tag> --format '{{.Architecture}}/{{.Os}}'
docker image inspect strategic-task-management:<tag> --format '{{.Architecture}}/{{.Os}}'
```

Both must print:

```text
amd64/linux
```

After loading images on the server:

```bash
docker compose ps
curl -fsS http://127.0.0.1:18080/api/v1/actuator/health
curl -fsS http://127.0.0.1:18081/ >/dev/null
```

Only switch nginx after these checks pass.
