# SISM Ubuntu 24.04 生产换机部署手册

本文描述把现有 SISM 生产栈迁移到一台全新的 Ubuntu 24.04 X64
服务器时，必须满足的主机契约、Runner 拓扑和发布顺序。

## 1. 部署架构

生产镜像不会在服务器上构建：

1. GitHub 托管的 `ubuntu-24.04` Runner 完成测试和 Docker 构建。
2. Actions artifact 保存压缩后的 Docker 镜像。
3. 服务器自托管 Runner 下载 artifact 并执行 `docker load`。
4. 后端仓库的服务器 Runner 负责 Docker Compose 部署和健康检查。

后端和前端仓库各需要一个自托管 Runner。两者可以安装在同一台服务器，
也可以安装在共享同一 Docker daemon 的执行环境中。

## 2. 新服务器最低要求

- Ubuntu 24.04 X64。
- 至少 2 GB 内存，推荐 4 GB；至少 1 GB Swap。
- `/opt` 所在磁盘至少保留 10 GB，推荐 30 GB 以上。
- Docker Engine 和 Docker Compose v2。
- 能访问 GitHub Actions artifact、GitHub API 和 GHCR。
- 生产入口仍由反向代理转发：
  - `127.0.0.1:18080` → 后端容器 `8080`
  - `127.0.0.1:18081` → 前端容器 `80`

## 3. 初始化主机

从后端仓库执行：

```bash
sudo ./scripts/deployment/bootstrap-docker-host.sh sism-runner
```

该脚本会：

- 安装 Ubuntu 所需运行包、Docker Engine 和 Compose 插件。
- 创建或复用 `sism-runner` 用户。
- 创建 `/opt/sism-stack/production`。
- 配置受信任 Runner 所需的 Docker 权限和免密 sudo。
- 执行与生产工作流相同的主机预检。

## 4. 注册两个 GitHub Runner

Runner 必须注册到各自仓库，名称可自定义，但标签必须包含：

后端仓库：

```text
self-hosted,Linux,X64,backend,production
```

前端仓库：

```text
self-hosted,Linux,X64,frontend,production
```

安装完成后，将两套 Runner 都注册为 systemd 服务并确认 GitHub 显示
`online`。工作流不依赖服务器 IP 选择 Runner，只依赖上述标签。

## 5. GitHub 配置

确认两个仓库的 Actions 可以读取所需配置：

- 后端仓库：`SERVER_HOST`、`GHCR_USERNAME`、`GHCR_TOKEN`。
- 前端仓库：能够触发后端 workflow dispatch 的 `GHCR_TOKEN`。
- `SERVER_HOST` 应使用生产域名或完整生产 Origin；DNS 和 TLS 应提前切换。

如果服务器需要代理，应把代理变量配置到两套 Runner 的 systemd 服务，
而不是只设置交互式 shell 或图形界面的系统代理。

## 6. 首次发布顺序

空服务器推荐按以下顺序初始化：

1. 合并或手动触发后端 `full-stack` 工作流，安装 Compose 栈并启动数据库、
   后端和当前前端镜像。
2. 合并或触发前端工作流，加载精确 SHA 前端镜像。
3. 前端工作流触发后端 `frontend-only` 部署。
4. 验证容器、健康端点、登录页和核心 API。

`frontend-only` 工作流现在也会传输并安装 Compose 引导包，因此空服务器上
不会再因为缺少 `/opt/sism-stack/production/docker-compose.yml` 而直接失败；
但完整可用的业务系统仍应以后端 `full-stack` 初始化为准。

## 7. 生产数据迁移

CI/CD 只负责程序、配置模板和容器，不会自动把旧服务器的业务数据复制到新机。
正式切换前必须单独迁移：

- PostgreSQL 生产数据库备份和恢复。
- `/opt/sism-stack/production/.env` 中的生产密钥与数据库配置。
- `backend_uploads` 和 `backend_exports` Docker volume。
- 主机反向代理、证书和 DNS。

不要把旧 `.env`、数据库备份或附件提交到 GitHub artifact 或仓库。

## 8. 换机验收

```bash
cd /opt/sism-stack/production
docker compose ps
docker compose images
curl -fsS http://127.0.0.1:18080/api/v1/actuator/health
curl -fsS http://127.0.0.1:18081/ >/dev/null
```

然后使用主域名验证：

```bash
curl -fsS https://blackevil.cn/api/v1/actuator/health
curl -fsS -o /dev/null -w '%{http_code}\n' https://blackevil.cn/
```

发布成功标准：三个 Compose 服务均 healthy，后端状态为 `UP`，前端和五个
核心 API 冒烟检查全部返回 HTTP 200。
