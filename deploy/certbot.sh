#!/bin/sh
# 证书容器：按 SITE_DOMAINS 向 Let's Encrypt 申请证书（http 验证，验证文件放在和 nginx 共用的目录里），
# 申请到以后每 12 小时检查一次，快到期就自动续期；nginx 那边发现证书文件变了会自己 reload。
# 申请失败（域名没解析好、80 端口不通、国内服务器域名没备案被拦截）时 3 小时后再试，避免撞 Let's Encrypt 的失败次数限制。
if [ -z "$SITE_DOMAINS" ]; then
    echo "没配置 SITE_DOMAINS，不申请证书"
    exec sleep infinity
fi
FIRST=$(echo "$SITE_DOMAINS" | awk '{print $1}')
ARGS=""
for d in $SITE_DOMAINS; do ARGS="$ARGS -d $d"; done
if [ -n "$CERTBOT_EMAIL" ]; then CONTACT="--email $CERTBOT_EMAIL"; else CONTACT="--register-unsafely-without-email"; fi

# 等 nginx 起来，验证文件才能被访问到
sleep 15
while :; do
    if [ ! -f "/etc/letsencrypt/live/$FIRST/fullchain.pem" ]; then
        echo "申请证书：$SITE_DOMAINS"
        if certbot certonly --webroot -w /var/www/certbot $ARGS --cert-name "$FIRST" \
            --agree-tos --non-interactive $CONTACT; then
            echo "证书申请成功"
        else
            echo "证书申请失败，3 小时后再试（检查域名解析、80 端口、备案）"
            sleep 3h
            continue
        fi
    else
        certbot renew --webroot -w /var/www/certbot --quiet || echo "续期检查失败，12 小时后再试"
    fi
    sleep 12h
done
