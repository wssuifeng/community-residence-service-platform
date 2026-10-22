# 阿里云 ECS 云端发布步骤

本文件面向毕业设计演示环境。云端交付采用“本机制作应用镜像、推送镜像仓库、ECS 拉取固定版本、Compose 启动”的方式，不直接上传本机运行中的容器。

## 1. 交付边界

不上传本机 `uploads/`、`.env`、数据库卷、`backend/target/` 或 `frontend/dist/`。云端使用独立的 `cloud_uploads` 卷，首次部署不会自动显示本机图片。

```text
本机源码 -> JAR/dist -> backend/frontend镜像 -> 镜像仓库 -> ECS pull -> Compose启动
```

## 2. 本机制作并推送镜像

本批次已使用阿里云香港 ACR 个人版。后续版本应继续使用新的不可变标签，不使用 `latest`：

```powershell
Push-Location backend
.\mvnw.cmd -B -DskipTests package
Pop-Location
Set-Location frontend
npm run build
Set-Location ..

docker build -f docker/backend.artifact.Dockerfile -t <registry>/<namespace>/backend:<tag> .
docker build -f docker/frontend.artifact.Dockerfile -t <registry>/<namespace>/frontend:<tag> .
docker login <registry>
docker push <registry>/<namespace>/backend:<tag>
docker push <registry>/<namespace>/frontend:<tag>
```

版本标签使用不可变发布标识，不使用 `latest`。推送后记录两个镜像 digest，并写入服务器 `.env.cloud`。私有仓库在 ECS 上使用最小拉取权限登录。

最近一次镜像更新（2026-10-22，已推送到 ACR；未部署到 ECS）：

| 产物 | 指纹 |
|---|---|
| 后端镜像 | `crpi-1trewaz90eq5799a.cn-hongkong.personal.cr.aliyuncs.com/community-residence/backend:cloud-20261022-r2` |
| 后端远程 digest | `sha256:81225d27901943cd5faf89cd2eb95f4619b1adb4fd6272ad1a33f9e137064027` |
| 前端镜像 | `crpi-1trewaz90eq5799a.cn-hongkong.personal.cr.aliyuncs.com/community-residence/frontend:cloud-20261022-r2` |
| 前端远程 digest | `sha256:8e030842682370ceba0a99c01ab12e173b7de395ea05ce4846bbe4f18df99f8c` |
| 后端 JAR SHA-256 | `480B9C5106D207FAD3823A6EF1880968191F2F26C97CCFCD9B1EDDD5DBA86B68` |
| 包含变更 | 后端移除未使用的 `GUEST` 账号角色常量（不改变权限/API行为），并包含 Flyway V46 列注释同步；前端账号角色为四类，未登录访问无角色，公告目标界面标为“无需居住关系可见” |

本次只更新 ACR 镜像，没有连接 ECS 或执行部署。后端 `cloud-20261022-r2` 替代前一版 `r1`，用于去除未使用的伪角色常量并同步注释/匿名测试语义；前端 `cloud-20261022-r2` 是当前版，`r1` 已被替代。用户已于 2026-10-22 释放 ECS；当前镜像留待后续重新创建服务器时使用。新后端启动时 Flyway 会应用 V46。

首次部署构建（2026-10-21，已推送并完成 ECS 验证）：

| 产物 | 指纹 |
|---|---|
| 后端本地标签 | `community-residence-backend:cloud-20261021-r1` |
| 前端本地标签（首次发布） | `community-residence-frontend:cloud-20261021-r1` |
| 前端本地标签（公网入口修正版） | `community-residence-frontend:cloud-20261021-r2` |
| ACR Registry | `crpi-1trewaz90eq5799a.cn-hongkong.personal.cr.aliyuncs.com` |
| ACR 命名空间 | `community-residence` |
| ECS 公网 IP | `47.238.196.17` |
| 后端远程引用 | `crpi-1trewaz90eq5799a.cn-hongkong.personal.cr.aliyuncs.com/community-residence/backend:cloud-20261021-r1` |
| 前端远程引用 | `crpi-1trewaz90eq5799a.cn-hongkong.personal.cr.aliyuncs.com/community-residence/frontend:cloud-20261021-r1` |
| 后端远程 digest | `sha256:07c5900ad8683e96324e1f9b097e54895afcf3250f2dabf7029747e772b8a9a6` |
| 前端远程 digest | `sha256:09eff9291da2f3f66787dd91cf9f0c0128f67c2778378e8a788cb24b2cabd721` |
| 前端最终远程引用 | `crpi-1trewaz90eq5799a.cn-hongkong.personal.cr.aliyuncs.com/community-residence/frontend:cloud-20261021-r2` |
| 前端最终远程 digest | `sha256:1c5c026e7cd116b2b96039ae9fa98bc138e7fbd222a50f2637bf1706ef2cf97a` |
| 后端 JAR SHA-256 | `857B9852F50205E938D4FDDBF9822FAEAA556E54B4C01B41895B39562D817BF7` |
| 后端镜像本地 ID | `sha256:1b858c3d616b2bdb35f331f76cbd0ba14bebbdde886ecdb9387b8ce463dfc2cd` |
| 前端镜像本地 ID | `sha256:a439dfc83c4491e0bd9def2b8184fe6b9f4c2a366974683117de8a7e8d3578f1` |

后端本次使用 JDK 17 执行 `-DskipTests package`，该步骤只验证发布构建，不代表重新执行全量测试；全量测试结论沿用 50 阶段记录。

## 3. ECS 初始化

使用创建实例时的密钥，以 `ecs-user` 登录：

```bash
ssh -i /path/to/key.pem ecs-user@<ECS公网IP>
docker version
docker compose version
timedatectl
df -h
free -h
```

Docker 运行环境镜像已提供 Docker 和 Compose，不需要在服务器安装 Java、Maven、Node.js 或 npm。建议统一时区并检查 swap：

```bash
sudo timedatectl set-timezone Asia/Shanghai
free -h
```

如果没有 swap，再按服务器实际情况创建 2 GiB swap；不要重复创建已有的 `/swapfile`。

## 4. 上传部署文件

服务器只需要 `docker-compose.cloud.yml`、`docker/mysql/init/01-grant-flyway-performance-schema.sh` 和只存在于服务器的 `.env.cloud`。可通过 Git 拉取已提交版本，或用 `scp` 只上传上述文件。

```bash
cp .env.cloud.example .env.cloud
chmod 600 .env.cloud
nano .env.cloud
```

填写固定的 `BACKEND_IMAGE`、`FRONTEND_IMAGE`、三个不同的数据库/Redis密码、至少32字符的 `JWT_SECRET`，并保持 `FRONTEND_HOST_PORT=8088`。不要把 `.env.cloud` 回传 Git。

## 5. 启动验证

```bash
docker login <registry>
docker compose --env-file .env.cloud -f docker-compose.cloud.yml config
docker compose --env-file .env.cloud -f docker-compose.cloud.yml pull
docker compose --env-file .env.cloud -f docker-compose.cloud.yml up -d --wait
docker compose --env-file .env.cloud -f docker-compose.cloud.yml ps
docker compose --env-file .env.cloud -f docker-compose.cloud.yml logs --tail=200 backend
curl -I http://127.0.0.1:8088/
curl -I http://127.0.0.1:8088/v3/api-docs
```

Redis 已启用密码，后端通过 `SPRING_DATA_REDIS_PASSWORD` 连接。MySQL、Redis 和后端不发布公网端口，前端只绑定 `127.0.0.1:8088`。

## 6. 公网入口

公网只开放安全组中的 80/443。宿主机反向代理转发到 `http://127.0.0.1:8088`，并为 `/ws/` 保留 WebSocket Upgrade 头。不要开放 8080、8088、3306 或 6379。域名解析到 ECS 公网 IP 后再配置 HTTPS。

## 7. 更新与回滚

先推送新的不可变镜像，再修改服务器 `.env.cloud` 的镜像地址并执行：

```bash
docker compose --env-file .env.cloud -f docker-compose.cloud.yml pull
docker compose --env-file .env.cloud -f docker-compose.cloud.yml up -d --wait
```

回滚时改回上一版本 digest。普通更新不要删除 MySQL、Redis 和 `cloud_uploads` 卷。

## 8. ECS 首次部署验证记录（2026-10-21）

已完成：

- ACR 香港个人版已完成 backend `cloud-20261021-r1` 和 frontend `cloud-20261021-r2` 推送；
- ECS 公网 IP 已确认：`47.238.196.17`；
- ECS 已通过 SSH 完成主机检查，Docker Engine `29.3.1`、Compose `v5.1.1`；
- 云端 Compose 四服务已启动，MySQL/Redis healthy，backend Flyway V45；
- 宿主机 Nginx 已安装并监听 `80`，代理到 `127.0.0.1:8088`；
- IP 首页、OpenAPI/Swagger、公共只读接口、四角色登录、`/ws/info` 和用户主动上传图片已完成部署级冒烟；
- 本机 `uploads/` 未上传。云端图片卷中的演示图片来自用户主动上传，不是自动同步。

域名与 TLS 收尾记录：

- 域名 `cloud-computing-webstatic.cc.cd` 已绑定到 ECS，A 记录指向 `47.238.196.17`，HTTP 入口已验证；
- HTTPS 已使用 Certbot 申请并配置，443 已启用，HTTP 已重定向到 HTTPS；证书有效期至 `2027-01-19`，Certbot 自动续期定时器已启用；
- 云端数据库/`cloud_uploads` 上传卷备份与恢复演练：用户裁决不执行，因本项目为短期毕业设计演示环境且数据不重要；
- 阿里云余额或费用告警：用户裁决不配置，由用户自行控制余额和使用时长；
- Docker 服务已配置为开机自启，四个 Compose 服务均为 `restart: unless-stopped`。下次开机先检查：

```bash
cd /home/ecs-user/community-residence
sudo docker compose --env-file .env.cloud -f docker-compose.cloud.yml ps
```

通常无需手动启动容器。若状态不是运行中，再执行：

```bash
sudo docker compose --env-file .env.cloud -f docker-compose.cloud.yml up -d --wait
```

证书自动续期需要 ECS 在续期窗口内处于运行状态，并且域名仍指向该 ECS、`80/443` 可从公网访问。若服务器长期关闭，重新开机后先检查证书有效期；临近到期时再执行 `sudo certbot renew`。

## 9. 当前云端状态（2026-10-22）

- 原 ECS 资源已由用户释放，不存在需要检查、启动或更新的服务器；以上 IP、Nginx、证书和容器状态均为释放前的历史验证记录。
- ACR 当前推荐镜像为 backend `cloud-20261022-r2`、frontend `cloud-20261022-r2`，digest 见本文件第 2 节；该组合尚未部署或运行验证。
- 后续重新创建 ECS 时，使用更新后的 `.env.cloud.example` 固定镜像引用，再按本文件执行部署；不需要恢复已释放实例。
