# SISM 主线发布与离线手动部署操作手册

> 最后更新：2026-10-26（**拓扑定死**：删除 `deploy` / `stage-on-server` job，GitHub 侧不再有任何触达服务器的代码；落服只走本地三步）

## 1. 文档目的

本文档说明 SISM 的发布方式、分支职责、**离线手动部署链路**、验证方法与故障排查。

**先纠正一个常见误解**：本项目**没有自动部署**。把代码推上 `main` 只会触发 CI 校验与镜像构建（产物推到 GHCR），**不会**把任何东西送到服务器。真正落服必须由人手动走完三步离线链路。

### 1.1 三道部署安全门

| 门 | 机制 | 效果 |
|---|---|---|
| ① 代码进仓库 ≠ 部署 | workflow 里**根本没有部署 job**（2026-10-26 已删除 `deploy` 与 `stage-on-server`） | push 到 `main` 只构建镜像与产物，GitHub 侧没有任何能触达服务器的代码 |
| ② 落服只能从本地发起 | 只有本机能执行 `upload-offline-release.sh` / `install-offline-release.sh` | 服务器不被 GitHub 直接触达，也不主动拉取 |
| ③ 产物落地靠人工搬运 | 产物先在本地成形（含逐文件 `sha256`），再由本机推送到服务器 | 全程可校验、可中断、可重跑 |

> 校验现状（两仓都应有空输出）：
> `grep -rnE "self-hosted|docker compose up|download-artifact" .github/workflows/`

### 1.2 服务器与仓库现状

- 服务器目标：`175.24.139.148`（blackevil.cn）
- 服务器容器运行时：**Docker 29.1.4 + Compose v5.0.1**（该机未安装 podman）
- 部署目录：`/opt/sism-stack/production`
- 离线包存放：`/opt/sism-releases/`
- 镜像仓库：GHCR（`ghcr.io/<owner>/sism-backend`、`ghcr.io/<owner>/strategic-task-management`）
- GitHub secrets 现在只剩构建所需（`GITHUB_TOKEN`）；`SERVER_*`、`GHCR_*` 等落服用 secret 已不再被任何 workflow 读取，可择机清理

## 2. 分支与仓库职责

### 2.1 仓库职责

| 仓库 | 负责 |
|---|---|
| `sism-backend` | 后端代码、后端镜像、**整套系统栈的部署编排**（compose + 离线脚本 + 部署 workflow） |
| `strategic-task-management` | 前端代码、前端镜像、前端构建校验 |

> 说明：整套栈的 compose 定义、离线脚本、离线包组装都以 `sism-backend` 为准；前端仓库只产出前端镜像与 `frontend-image-<sha>` 产物，不单独维护部署逻辑。

### 2.2 分支职责

| 分支 | 用途 |
|---|---|
| `main` | 稳定主线。合并到这里 = 进入正式线并触发构建，**但不落服** |
| `develop` | 日常集成分支。功能/修复先合到这里 |
| `feature/*` `fix/*` `chore/*` | 短分支，合入 `develop` 后立即删除 |
| `hotfix/*` | 从 `main` 拉，修完合回 `main` 并回灌 `develop` |

分支规则的唯一权威见 `docs/提交与分支工作流.md`。`baseline/*`、`test/*`、`dev/*` 已废止，历史内容转为 `archive/<repo>/<branch>` 标签。

### 2.3 合并原则

1. 功能与修复一律从 `develop` 拉短分支
2. PR 合并进 `develop` 用 squash，合并即删分支
3. 验收通过后 `develop` 用 `git merge --no-ff` 合进 `main`（保留发布合并点）
4. 发版后把 `develop` 快进对齐到 `main`
5. 严禁在 `main` 上直接提交

## 3. 发布链路（离线手动）

### 3.1 阶段一：合并到 main（不落服）

`main` push 后，两仓库各自执行：

| 仓库 | workflow | 做的事 | 不做的事 |
|---|---|---|---|
| `sism-backend` | `Build and Push Backend` | QA 校验（后端 verification / Flyway 命名检查）→ 准备部署 bundle → 构建后端镜像 → 推 GHCR → 导出镜像 artifact | **不连服务器**（workflow 内已无任何 `self-hosted` job） |
| `strategic-task-management` | `Build and Push Frontend Image` | QA 校验（架构基线 / type-check / 单测 / 生产构建）→ 构建前端镜像 → 推 GHCR → 导出镜像 artifact | **不连服务器**（`stage-on-server` 已删除） |

产物有两处可取：
- **GHCR 镜像**（tag 含 commit SHA 与滚动 `main`）
- **Actions artifact**（`backend-stack-<sha>`、`backend-image-<sha>`、`frontend-image-<sha>` 等）

### 3.2 阶段二：下载产物到本地

```bash
cd sism-backend/scripts/deployment
./download-offline-release.sh --output ../dist/offline-releases
# 可显式指定 run：--backend-run <id> --frontend-run <id>
```

脚本会拉取两个仓库最近一次成功的 main 构建，产出离线发布包（镜像归档 + compose + 种子 + 校验脚本）。

### 3.3 阶段三：上传到服务器

```bash
./upload-offline-release.sh --archive <包.tar.gz> --remote root@175.24.139.148
# 只想先传不部署：加 --upload-only
```

### 3.4 阶段四：服务器落服

```bash
# 服务器上执行（或用 --env-file 先装生产 .env）
./install-offline-release.sh --target /opt/sism-stack/production
# 只校验不落地：--verify-only
```

`install-offline-release.sh` 依次完成：校验 manifest 与 checksum → 安装镜像归档 → `docker compose up -d` → 等待健康检查通过 → 失败自动回滚上一版镜像。

### 3.5 全流程一览

```
① 合并到 main ──► CI 校验 + 构建镜像 + 推 GHCR（不落服）
                              │
② download-offline-release.sh （本地）        拉取产物
                              │
③ upload-offline-release.sh    （本地→服务器）  上传
                              │
④ install-offline-release.sh   （服务器）      校验→落服→健康检查→失败回滚
```

**任何一步都可以人工介入**：② ③ ④ 都是本地/服务器上的普通脚本，可单独重跑。

## 4. 日常开发与发布

### 4.1 日常开发

1. 在 `develop` 或短分支（`feature/*`、`fix/*`）上开发
2. 本地最小验证
   - 后端：`./mvnw -pl sism-main -am -DskipTests package`
   - 前端：`npm run build`
3. 提交到远端分支
4. 阶段验收通过后合并到 `main`

### 4.2 正式发布

1. 合并前端到 `main` → 等待其 CI 与镜像构建完成
2. 合并后端到 `main` → 等待其 CI 与镜像构建完成
3. 若为联动版本，确认前后端 `main` 落在预期提交
4. 走 §3.2 → §3.4 的离线三步
5. 发布后把 `develop` 快进对齐到 `main`

### 4.3 首次部署

首次部署不要求人工先写完整业务 `.env`——部署流程会在服务器生成或维护：

`POSTGRES_DB`、`POSTGRES_USER`、`POSTGRES_PASSWORD`、`JWT_SECRET`、`ALLOWED_ORIGINS`、`FRONTEND_IMAGE`、`BACKEND_IMAGE`、`SPRING_FLYWAY_ENABLED`、`BACKEND_PORT`、`FRONTEND_PORT`、`JAVA_HEAP_SIZE`、`JAVA_METASPACE_SIZE`、`JAVA_STACK_SIZE`、`DB_HIKARI_MAX_POOL_SIZE`、`DB_HIKARI_MIN_IDLE`

### 4.4 数据库边界

- 标准生产路径只面向 compose 管理的 PostgreSQL 容器
- 服务器上手动启动的 OpenTenBase / OTB 属临时实验，**不视为生产依赖**；正式部署前应先停掉，避免与生产容器栈争抢内存
- OTB 启停属于人工实验流程，不在部署链路内

## 5. 服务器与运行态

### 5.1 关键路径

- 部署目录：`/opt/sism-stack/production`
- 运行状态：`deploy-status.env`、`deploy-run.log`
- 旧 SCP 部署记录：`scp-deploy-status.env`、`scp-deploy-run.log`

### 5.2 常用检查命令

```bash
ssh root@175.24.139.148
cd /opt/sism-stack/production
docker ps --format "table {{.Names}}\t{{.Image}}\t{{.Status}}"
cat deploy-status.env
tail -200 deploy-run.log
curl -sS http://127.0.0.1:8080/api/v1/actuator/health
curl -I http://127.0.0.1/
```

### 5.3 健康口径

| 对象 | 检查 | 通过标准 |
|---|---|---|
| 后端 | `GET /api/v1/actuator/health` | `200` 且 body 含 `status=UP` |
| 前端 | `GET /` | `200 OK` |
| 容器 | `production_postgres_1` / `production_backend_1` / `production_frontend_1` | 均 `healthy` 或稳定 `Up` |

## 6. 故障排查

### 6.1 Actions 显示失败，但服务器其实已经起来

原因通常是校验太早（容器刚重建就单次 `curl`）。当前逻辑已改为带重试等待；再次遇到先做实机确认：

```bash
docker ps
curl http://127.0.0.1:8080/api/v1/actuator/health
curl -I http://127.0.0.1/
```

### 6.2 部署到一半失败

`install-offline-release.sh` 内置回滚：检查 `deploy-run.log` 末尾的回滚记录，确认已恢复到上一版镜像 tag。

### 6.3 产物版本对不上

- 服务器镜像 tag 应等于目标 commit SHA
- 用 `docker images` 核对本地 tag
- 用 `gh run list` 核对 run ID 与 SHA，再重新 `download-offline-release.sh`

## 7. 相关文档

- `docs/deployment-image-arch-note.md` — 镜像架构要求与历史替换复盘
- `docs/flyway-migration-guide.md` — Flyway 迁移命名与执行规范
- `docs/seed-change-system-admin-auto-approval.md` — 种子变更与系统管理员自动审批约定
- `docs/opentenbase-deployment-guide.md` — OpenTenBase 实验环境（非常规生产路径）
- `docs/Ubuntu-24.04-生产换机部署手册.md` — 换机操作
- `backend/scripts/deployment/README.md` — 离线脚本逐支说明
