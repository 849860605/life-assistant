# 前端镜像：打包 uni-app H5，用 nginx 提供页面、把 /api 转给后端；配了域名且证书申请到后自动开 HTTPS。
ARG MIRROR=

FROM ${MIRROR}library/node:20-bookworm-slim AS build
ARG NPM_REGISTRY=https://registry.npmmirror.com
WORKDIR /web
COPY life-web/package.json life-web/package-lock.json ./
RUN npm ci --registry=${NPM_REGISTRY}
COPY life-web/ ./
RUN npm run build:h5

FROM ${MIRROR}library/nginx:1.27-alpine
ENV TZ=Asia/Shanghai
COPY deploy/nginx/app.conf /etc/nginx/snippets/app.conf
COPY deploy/nginx/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh && mkdir -p /var/www/certbot
COPY --from=build /web/dist/build/h5 /usr/share/nginx/html
EXPOSE 80 443
ENTRYPOINT ["/entrypoint.sh"]
