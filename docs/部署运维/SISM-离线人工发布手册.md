# SISM 离线人工发布手册

该流程用于目标服务器无法稳定访问 GitHub、GitHub Actions artifact 或 GHCR
的场景。GitHub 只负责测试、构建和保存镜像；操作者的本地电脑负责下载并通过
SSH/SCP 中转，服务器部署阶段不访问 GitHub。

## 发布拓扑

```text
GitHub 托管 Runner
  ├─ 构建后端镜像 artifact
  ├─ 构建前端镜像 artifact
  └─ 保存 Compose 部署包
             ↓ gh CLI
操作者本地电脑
  ├─ 下载两个仓库的成功产物
  ├─ 校验 gzip
  ├─ 生成 manifest.env 和 SHA256SUMS
  └─ 组装完整离线 tar.gz
             ↓ SSH/SCP
Ubuntu/Linux 目标服务器
  ├─ 校验 archive 和文件 SHA256
  ├─ docker load
  ├─ docker compose up
  ├─ 健康检查
  └─ 失败时恢复旧镜像引用
```

## GitHub Actions 行为

- 推送 `main`：执行 QA、构建镜像并上传保留 7 天的 artifact，不连接服务器。
- 手动运行且选择 `artifact-only`：只构建 artifact。
- 手动运行且选择 `test-server`：允许使用现有自托管 Runner 部署测试服务器。
- 生产服务器不需要安装 GitHub Runner，也不需要访问 GitHub 或 GHCR。

## 本地下载并组装

本地电脑需要 `gh`、`jq`、`tar`、`gzip` 和 `shasum`/`sha256sum`：

```bash
cd sism-backend
gh auth status
./scripts/deployment/download-offline-release.sh \
  --output "$HOME/Downloads/sism-releases"
```

脚本默认选择两个仓库在 `main` 上最近一次成功的 push 构建。也可以固定运行号：

```bash
./scripts/deployment/download-offline-release.sh \
  --backend-run 123456789 \
  --frontend-run 987654321 \
  --output "$HOME/Downloads/sism-releases"
```

输出包含：

- `sism-offline-<backend>-<frontend>.tar.gz`
- `sism-offline-<backend>-<frontend>.tar.gz.sha256`

离线包不包含生产 `.env`、数据库备份、附件或 TLS 私钥。

## 上传并部署

目标服务器必须先安装 Docker Engine 和 Docker Compose，并准备生产 `.env`。
换机时应从旧服务器安全迁移 `/opt/sism-stack/production/.env`。

```bash
./scripts/deployment/upload-offline-release.sh \
  --archive "$HOME/Downloads/sism-releases/sism-offline-<版本>.tar.gz" \
  --remote root@服务器地址 \
  --target /opt/sism-stack/production
```

只上传并验证、不启动容器：

```bash
./scripts/deployment/upload-offline-release.sh \
  --archive "$HOME/Downloads/sism-releases/sism-offline-<版本>.tar.gz" \
  --remote root@服务器地址 \
  --upload-only
```

首次部署时也可以显式传入本地保存的生产环境文件：

```bash
./scripts/deployment/upload-offline-release.sh \
  --archive "$HOME/Downloads/sism-releases/sism-offline-<版本>.tar.gz" \
  --remote root@服务器地址 \
  --env-file /安全路径/production.env
```

## 换机时仍需迁移的状态

- PostgreSQL 数据库备份和恢复。
- 生产 `.env` 和 JWT/数据库密钥。
- `backend_uploads`、`backend_exports` 等数据卷。
- Nginx/反向代理、TLS 证书和 DNS。

这些状态不得写入 GitHub artifact 或代码仓库。

## 验收标准

安装脚本成功后必须满足：

- PostgreSQL（内部数据库模式）、后端、前端容器健康。
- 本机后端健康接口返回 `UP`。
- 本机前端端口返回 HTTP 200。
- `offline-deploy-status.env` 记录前后端精确 SHA。
- 公网登录页、健康接口和核心 API 通过。

