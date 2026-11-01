#!/usr/bin/env bash
# 一键部署：检查 .env，按需切到国内镜像站，构建并启动前端、后端、数据库、Redis。
# 用法（在项目根目录或任意目录）：deploy/deploy.sh
# 可选环境变量：WEB_PORT（默认 80）、IMAGE_MIRROR（镜像站前缀，不设则自动判断）
set -euo pipefail
cd "$(dirname "$0")/.."

if ! docker compose version >/dev/null 2>&1; then
  echo "没找到 docker compose，请先安装 Docker（含 compose 插件）" >&2
  exit 1
fi

if [ ! -f .env ]; then
  cp .env.example .env
  echo "已从 .env.example 生成 .env，请先填好里面的密码和 Key，再运行一次 deploy/deploy.sh" >&2
  exit 1
fi

# 读 .env 里的某一项（不执行文件内容）
env_value() { grep -E "^$1=" .env | tail -n 1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//"; }

missing=()
for key in LIFE_CRYPTO_KEY LIFE_USERNAME LIFE_PASSWORD DB_PASSWORD; do
  [ -n "$(env_value "$key")" ] || missing+=("$key")
done
if [ ${#missing[@]} -gt 0 ]; then
  echo ".env 里这些还没填：${missing[*]}" >&2
  exit 1
fi
if [ "$(env_value DB_PASSWORD)" = "life" ]; then
  echo "提醒：DB_PASSWORD 还是默认的 life，服务器上建议改成随机密码（数据库第一次启动后再改要进库里改）"
fi

# 国内服务器连不上 Docker Hub 时，基础镜像走 DaoCloud 镜像站
if [ -z "${IMAGE_MIRROR+x}" ]; then
  if curl -fsS -m 5 -o /dev/null https://registry-1.docker.io/v2/ 2>/dev/null || [ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' https://registry-1.docker.io/v2/ 2>/dev/null)" = "401" ]; then
    IMAGE_MIRROR=""
  else
    IMAGE_MIRROR="docker.m.daocloud.io/"
    echo "连不上 Docker Hub，基础镜像改从 ${IMAGE_MIRROR} 拉"
  fi
fi
export IMAGE_MIRROR

compose=(docker compose -f deploy/docker-compose.yml --env-file .env --profile app)
"${compose[@]}" up -d --build

echo "等后端启动…"
for _ in $(seq 1 60); do
  status=$(docker inspect -f '{{.State.Health.Status}}' "$("${compose[@]}" ps -q app)" 2>/dev/null || echo starting)
  if [ "$status" = "healthy" ]; then
    site=$(env_value SITE_DOMAINS | awk '{print $1}')
    if [ -n "$site" ]; then
      echo "部署好了：https://${site}（第一次要申请证书，一两分钟后生效；看进度：docker compose -f deploy/docker-compose.yml --profile app logs certbot）"
    else
      echo "部署好了：http://<服务器IP>:${WEB_PORT:-80}"
    fi
    exit 0
  fi
  sleep 5
done
echo "后端 5 分钟内没启动好，看日志：docker compose -f deploy/docker-compose.yml --profile app logs app" >&2
exit 1
