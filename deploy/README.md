# 一键部署

在一台装了 Docker（含 compose 插件）的服务器上，把前端、后端、PostgreSQL、Redis 一起跑起来。

## 步骤

1. 把项目拷到服务器，例如用 `git clone` 或 `rsync`。不要拷 `.env`。
2. 在项目根目录新建 `.env`。可以从 `.env.example` 复制，也可以把本地的 `.env` 单独传上去。至少要填：
   - `LIFE_CRYPTO_KEY`、`LIFE_USERNAME`、`LIFE_PASSWORD`
   - `DB_PASSWORD`：服务器上用随机密码
   - `REDIS_PASSWORD`：建议填，填了 Redis 会开密码
   - 模型 Key，例如 `DEEPSEEK_API_KEY`
   - 用到哪个平台就填哪个平台的 Key：`AMAP_KEY`、`AMAP_JS_KEY`、`AMAP_JS_SECURITY`、`FLYAI_API_KEY`、`TRIPAI_API_KEY`、`TUNIU_API_KEY` 等
3. 运行：

   ```bash
   deploy/deploy.sh
   ```

   第一次运行要构建镜像，大约需要 5 到 10 分钟。完成后打开 `http://服务器IP` 即可使用。

## 说明

- **对外端口**：只开放网页的 80 和 443 端口，可以用 `WEB_PORT=8080 HTTPS_PORT=8443 deploy/deploy.sh` 改成别的端口。接口通过同一个端口的 `/api` 访问。数据库和 Redis 只监听本机，不对外开放。
- **镜像站**：服务器连不上 Docker Hub 时，脚本会自动改用 `docker.m.daocloud.io`。也可以自己指定，例如 `IMAGE_MIRROR=xxx/ deploy/deploy.sh`。Maven 依赖走阿里云镜像，npm 依赖走 npmmirror。
- **更新**：拉取新代码后再运行一次 `deploy/deploy.sh`。数据存在 Docker 卷里，更新不会丢失：
  - `pg-data`：数据库
  - `redis-data`：缓存和登录状态
  - `app-data`：导出的文件
- **改数据库密码**：数据库第一次启动后，再改 `.env` 里的 `DB_PASSWORD` 不会生效，要先进数据库里改密码。
- **HTTPS**：在 `.env` 里加一行 `SITE_DOMAINS=example.com www.example.com`（多个域名用空格分隔），然后重新运行 `deploy/deploy.sh`。
  - certbot 容器会向 Let's Encrypt 申请证书，之后每 12 小时检查一次，快到期就自动续期。
  - nginx 每分钟检查一次证书文件，发现有新证书就自动重新加载，用域名访问会从 http 跳转到 https。
  - 用 IP 访问始终走 http，不跳转。
  - 想收到证书到期提醒，可以再填 `CERTBOT_EMAIL`。
  - 申请证书的前提：域名已经解析到服务器；安全组放行了 80 和 443 端口；用的是国内服务器的话，域名已经备案（没备案时阿里云会拦截验证请求，申请失败，certbot 每 3 小时重试一次）。
  - 证书存在 `letsencrypt` 卷里，重建容器不会丢。
- **管理后台**：地址是 `http://服务器IP/admin/`，接口走 `/admin/api/v1`，和 App 用户是两套账号。
  - **首个管理员**：在 `.env` 里填 `LIFE_ADMIN_USERNAME` 和 `LIFE_ADMIN_PASSWORD` 后重新部署。后端启动时发现还没有任何管理员，就用它建一个超级管理员；已经有管理员时这两项不再起作用，可以删掉。登录后建议马上在"修改密码"里换一个新密码。
  - **角色**：超级管理员能新建、停用管理员和重置其他管理员的密码；普通管理员能管理 App 用户（新建、重置密码、停用）、看总览和操作日志。不能停用自己，也不能停用最后一个超级管理员。
  - **密码规则**：8 到 64 位，同时有字母和数字。连续输错 5 次，这个账号暂停登录 15 分钟。
  - **停用和重置密码**：停用用户或重置密码后，这个人已登录的设备会立刻退出。
  - **隐私**：后台只能看到账号和用量统计（对话数、行程数、模型调用和费用），看不到对话内容和行程内容。
  - **安全**：现在是 http 访问，后台密码在网络上是明文传输。开 HTTPS 之前，尽量只在自己可信的网络里登录后台。
- **高德网页地图**：如果高德控制台给 JS Key 设了域名白名单，要把服务器的域名加进去。
- **常用命令**：

  ```bash
  docker compose -f deploy/docker-compose.yml --profile app logs -f app   # 看后端日志
  docker compose -f deploy/docker-compose.yml --profile app ps            # 看状态
  docker compose -f deploy/docker-compose.yml --profile app down          # 停止（数据保留）
  ```
