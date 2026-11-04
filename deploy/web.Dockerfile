# 前端镜像：打包 uni-app H5 和管理后台，用 nginx 提供页面、把 /api 和 /admin/api 转给后端；配了域名且证书申请到后自动开 HTTPS。
ARG MIRROR=

FROM ${MIRROR}library/node:20-bookworm-slim AS build
ARG NPM_REGISTRY=https://registry.npmmirror.com
WORKDIR /web
COPY life-web/package.json life-web/package-lock.json ./
RUN npm ci --registry=${NPM_REGISTRY}
COPY life-web/ ./
RUN npm run build:h5

# 管理后台（Vue3 + Element Plus），部署在 /admin/ 下
FROM ${MIRROR}library/node:20-bookworm-slim AS admin
ARG NPM_REGISTRY=https://registry.npmmirror.com
WORKDIR /admin
COPY life-admin/package.json life-admin/package-lock.json ./
RUN npm ci --registry=${NPM_REGISTRY}
COPY life-admin/ ./
RUN npm run build

FROM ${MIRROR}library/nginx:1.27-alpine
ENV TZ=Asia/Shanghai
COPY deploy/nginx/app.conf /etc/nginx/snippets/app.conf
COPY deploy/nginx/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh && mkdir -p /var/www/certbot
COPY --from=build /web/dist/build/h5 /usr/share/nginx/html
COPY --from=admin /admin/dist /usr/share/nginx/html/admin
EXPOSE 80 443
ENTRYPOINT ["/entrypoint.sh"]
