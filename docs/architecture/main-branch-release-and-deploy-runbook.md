# SISM 主线发布与自动部署操作手册

## 1. 文档目的

本文档用于说明当前 SISM 项目的标准发布方式、分支职责、自动部署链路、验证方法和常见故障排查方法。

当前已确认的系统原则如下：

- `sism-backend` 是整套系统的部署入口仓库
- 前后端的自动部署触发统一以各自仓库的 `main` 为准
- `baseline/*` 保留为当前阶段开发基线，但不再作为自动部署入口
- 服务器目标为 `47.108.249.115`

本文档面向后续开发、测试、验收、发布、值守和故障排查人员。

## 2. 当前工作模型

### 2.1 仓库职责

- 后端仓库：`sism-backend`
  - 负责后端代码、后端镜像、系统级 GitHub Deployment 记录、服务器部署执行
- 前端仓库：`strategic-task-management`
  - 负责前端代码、前端镜像、前端构建校验、前端镜像发布

### 2.2 分支职责

- `main`
  - 唯一自动部署入口
  - 提交到 `main` 会触发正式 CI 与自动部署链路
- `baseline/backend-verified-20260426`
  - 后端当前开发基线
  - 可继续承载阶段开发，但不直接承担自动部署职责
- `baseline/frontend-verified-20260426`
  - 前端当前开发基线
  - 可继续承载阶段开发，但不直接承担自动部署职责
- `codex/*`、`backup/*`、`actions-test-*`、`containerization-clean`
  - 仅作为历史留档、测试或临时分支
  - 不作为长期发布入口

### 2.3 合并原则

- 功能开发优先在对应 `baseline/*` 或明确的功能分支完成
- 需要发布时，将最终确认版本合并到 `main`
- 一旦发布完成，应尽量保持 `main` 与当前 `baseline/*` 同步
- 严禁让旧 `main` 反向覆盖当前基线代码

## 3. 自动部署链路

### 3.1 后端 `main` 推送后的链路

后端仓库 `.github/workflows/build-and-push-image.yml` 在 `main` push 后执行：

1. `QA`
   - `mvn clean verify`
   - Flyway migration filename 校验
2. `Build and Push`
   - 构建后端镜像
   - 推送两个 tag
     - 不可变 tag：`${GITHUB_SHA}`
     - 滚动 tag：`main`
3. `Deploy Stack`
   - 通过 SSH 将 compose 与 nginx 配置传到服务器
   - 初始化或更新 `/opt/sism-stack/production/.env`
   - 拉取镜像
   - 执行容器更新
   - 写入部署状态文件
   - 进行带重试的健康检查

### 3.2 前端 `main` 推送后的链路

前端仓库 `.github/workflows/build-and-push-image.yml` 在 `main` push 后执行：

1. `QA`
   - `npm ci`
   - 架构基线校验
   - `npm run type-check`
   - `npm test`
   - `npm run build`
2. `Build and Push`
   - 构建前端镜像
   - 推送两个 tag
     - 不可变 tag：`${GITHUB_SHA}`
     - 滚动 tag：`main`
   - 注入 `VITE_APP_COMMIT_HASH=${GITHUB_SHA}`
3. `Trigger backend system deployment`
   - 调用后端仓库 workflow_dispatch
   - 目标 ref：后端 `main`
   - `deploy_mode=frontend-only`
   - `frontend_tag=${GITHUB_SHA}`

### 3.3 为什么后端是系统入口

- GitHub Deployment 记录统一记在后端仓库
- 服务器上的 compose 栈由后端仓库 workflow 负责下发和执行
- 当前系统级环境引导、容器重建、健康校验、落服状态追踪都在后端 workflow 中实现

## 4. 日常开发与发布步骤

### 4.1 日常开发

1. 在对应仓库的 `baseline/*` 或功能分支上开发
2. 本地完成最小验证
   - 后端：`./mvnw -pl sism-main -am -DskipTests package`
   - 前端：`npm run build`
3. 提交到远端功能分支或基线分支
4. 阶段验收通过后，再将该批变更合并到 `main`

### 4.2 正式发布

推荐顺序：

1. 前端变更发布
   - 合并到前端 `main`
   - 触发前端 CI、镜像构建，并派发后端 `frontend-only` 部署
2. 后端变更发布
   - 合并到后端 `main`
   - 触发后端 CI、镜像构建、整套系统部署
3. 若本次为联动版本
   - 确保前后端 `main` 都落到目标提交
   - 再确认服务器上容器 tag 与预期 SHA 一致

### 4.3 发布后的基线同步

若本次 `main` 为新的正式阶段版本：

1. 将对应 `baseline/*` 快进到当前 `main`
2. 保持开发基线与已发布主线一致
3. 后续新开发继续从 `baseline/*` 起步

## 5. 服务器与运行态

### 5.1 关键路径

- 服务器部署目录：`/opt/sism-stack/production`
- 运行状态文件：
  - `/opt/sism-stack/production/deploy-status.env`
  - `/opt/sism-stack/production/deploy-run.log`
- 旧 SCP 部署记录：
  - `/opt/sism-stack/production/scp-deploy-status.env`
  - `/opt/sism-stack/production/scp-deploy-run.log`

### 5.2 常用检查命令

```bash
ssh root@47.108.249.115

cd /opt/sism-stack/production
podman ps --format "table {{.Names}}\t{{.Image}}\t{{.Status}}"
cat deploy-status.env
tail -200 deploy-run.log
curl -sS http://127.0.0.1:8080/api/v1/actuator/health
curl -I http://127.0.0.1/
```

### 5.3 当前健康口径

- 后端：
  - `GET /api/v1/actuator/health`
  - 必须返回 `200`
  - body 中必须包含 `status=UP`
- 前端：
  - `GET /`
  - 必须返回 `200 OK`
- 容器：
  - `production_postgres_1`
  - `production_backend_1`
  - `production_frontend_1`
  - 三者都应为 `healthy` 或稳定 `Up`

## 6. 首次部署与环境要求

### 6.1 必需 secrets

前后端仓库统一使用如下 secrets：

- `SERVER_HOST`
- `SERVER_USER`
- `SERVER_SSH_KEY`
- `GHCR_USERNAME`
- `GHCR_TOKEN`

### 6.2 首次部署自动生成内容

后端部署 workflow 首次运行时会在服务器生成或维护：

- `POSTGRES_DB`
- `POSTGRES_USER`
- `POSTGRES_PASSWORD`
- `JWT_SECRET`
- `ALLOWED_ORIGINS`
- `FRONTEND_IMAGE`
- `BACKEND_IMAGE`
- `SPRING_FLYWAY_ENABLED`
- `BACKEND_PORT`
- `FRONTEND_PORT`
- `JAVA_HEAP_SIZE`
- `JAVA_METASPACE_SIZE`
- `JAVA_STACK_SIZE`
- `DB_HIKARI_MAX_POOL_SIZE`
- `DB_HIKARI_MIN_IDLE`

即，首次部署不要求人工先写完整业务 `.env`，只要 GitHub secrets 到位即可拉起基础环境。

### 6.3 数据库边界

- 标准生产部署路径只面向 compose 管理的 PostgreSQL 容器。
- 如果服务器上手动启动过 OpenTenBase / OTB 作为临时实验数据库：
  - 不把它视为生产依赖
  - 不要求自动部署为它保留额外内存
  - 在正式部署前应先手动停止宿主机 OTB 进程，避免与生产容器栈争抢内存
- OTB 的启停与切换属于人工实验流程，不属于 `main` 自动部署链的一部分。

## 7. 故障排查手册

### 7.1 场景一：GitHub Actions 显示失败，但服务器其实已经起来

原因通常不是部署失败，而是校验太早。

本项目已经修复过一次这类问题：

- 旧逻辑：容器刚重建完成就立即单次 `curl`
- 新逻辑：带重试等待后端与前端变为可用

若再次出现类似情况，先做实机确认：

```bash
podman ps
curl http://127.0.0.1:8080/api/v1/actuator/health
curl -I http://127.0.0.1/
```

### 7.2 场景二：前端已构建成功，但服务器仍是旧前端

优先检查：

- 服务器上的前端镜像是否仍然使用可变标签缓存
- 当前 deploy workflow 是否对前端镜像执行了强制 `pull`
- `/opt/sism-stack/production/.env` 里的 `FRONTEND_IMAGE` 是否已切到预期 SHA

### 7.3 场景三：后端镜像已更新，但容器在小内存主机上反复重启

优先检查：

- 宿主机是否还跑着额外的 OpenTenBase / OTB 进程
- `/opt/sism-stack/production/.env` 中的 JVM 参数是否已透传到 compose backend 服务
- `dmesg -T | grep -i oom` 是否出现 `Killed process (java)`

低内存主机的默认兜底参数：

```properties
JAVA_HEAP_SIZE=384m
JAVA_METASPACE_SIZE=128m
JAVA_STACK_SIZE=512k
DB_HIKARI_MAX_POOL_SIZE=2
DB_HIKARI_MIN_IDLE=1
```

1. 前端 `Build and Push Frontend Image` 是否成功
2. 前端 workflow 的 `Trigger backend system deployment` 是否成功
3. 后端仓库是否出现新的 `workflow_dispatch` 运行
4. 服务器上的 `production_frontend_1` 镜像 tag 是否已变更

### 7.3 场景三：后端镜像已推送，但自动部署卡住

检查：

1. `deploy-status.env`
2. `deploy-run.log`
3. `podman ps`
4. `podman compose ps` 或 `podman-compose ps`
5. SSH 是否能正常执行 compose 重建

### 7.4 场景四：本地仓库 `fetch/pull/status` 行为异常

检查：

1. `git config --get-all remote.origin.fetch`
2. `git branch -vv`

标准 fetch 配置应为：

```bash
+refs/heads/*:refs/remotes/origin/*
```

若被旧测试分支污染成单分支 fetch，需要修正后再继续。

## 8. 发布检查清单

### 8.1 发版前

- 目标变更已在本地验证
- 目标分支已明确
- 不在 `codex/*` 或临时备份分支上直接发布
- 若要发版，必须进入 `main`

### 8.2 发版中

- GitHub Actions 是否由 `main` 触发
- 前后端镜像是否发布成功
- 后端部署是否写入 `deploy-status.env`

### 8.3 发版后

- 容器 image tag 是否匹配目标 SHA
- 后端健康检查是否 `UP`
- 前端首页是否 `200`
- 必要时再补业务烟雾验证

## 9. 目前已验证通过的事实

截至本手册落地时，以下模式已被实机验证：

- 前端 `main` 推送会自动构建镜像，并成功派发后端 `frontend-only` 部署
- 后端 `main` 推送会自动完成 CI、镜像构建、服务器部署、健康校验
- 服务器 `47.108.249.115` 上可稳定运行前后端最新 `main` 版本
- 后端部署 workflow 的健康等待逻辑已修正，不再因为服务启动窗口导致误判失败

## 10. 后续维护建议

- 后续若再调整部署策略，统一优先改后端仓库文档与 workflow
- 若新增分支模型，必须同步更新本文档中的“分支职责”和“发布步骤”
- 如果未来决定废弃 `baseline/*`，需要先完成一次基线迁移说明，再删文档中的相关章节
- GitHub Actions 的 Node 20 弃用告警目前不阻塞部署，但应在后续平台维护中统一升级相关 action 版本
