#!/bin/sh
# 按 SITE_DOMAINS 和证书是否已经申请到，生成 nginx 配置后启动；之后每分钟看一次证书文件，
# 证书第一次申请到或续期后自动重新生成配置并 reload，不用手动重启。
# - 用 IP 访问：始终走 http，不跳转（备案前、证书还没下来时也能用）
# - 用域名访问：有证书就 http 跳 https，没证书先走 http
set -e

FIRST=$(echo "$SITE_DOMAINS" | awk '{print $1}')
CERT_DIR="/etc/letsencrypt/live/$FIRST"
CONF=/etc/nginx/conf.d/default.conf

render() {
    {
        echo "server {"
        echo "    listen 80 default_server;"
        echo "    server_name _;"
        echo "    location /.well-known/acme-challenge/ { root /var/www/certbot; }"
        echo "    include /etc/nginx/snippets/app.conf;"
        echo "}"
        if [ -n "$FIRST" ] && [ -f "$CERT_DIR/fullchain.pem" ]; then
            echo "server {"
            echo "    listen 80;"
            echo "    server_name $SITE_DOMAINS;"
            echo "    location /.well-known/acme-challenge/ { root /var/www/certbot; }"
            echo "    location / { return 301 https://\$host\$request_uri; }"
            echo "}"
            echo "server {"
            echo "    listen 443 ssl;"
            echo "    http2 on;"
            echo "    server_name $SITE_DOMAINS;"
            echo "    ssl_certificate $CERT_DIR/fullchain.pem;"
            echo "    ssl_certificate_key $CERT_DIR/privkey.pem;"
            echo "    ssl_protocols TLSv1.2 TLSv1.3;"
            echo "    ssl_session_cache shared:SSL:10m;"
            echo "    include /etc/nginx/snippets/app.conf;"
            echo "}"
        fi
    } > "$CONF"
}

stamp() {
    stat -c %Y "$CERT_DIR/fullchain.pem" 2>/dev/null || echo none
}

render
if [ -n "$FIRST" ]; then
    (
        last=$(stamp)
        while sleep 60; do
            now=$(stamp)
            if [ "$now" != "$last" ]; then
                last=$now
                render && nginx -s reload && echo "证书有更新，nginx 已重新加载"
            fi
        done
    ) &
fi
exec nginx -g "daemon off;"
