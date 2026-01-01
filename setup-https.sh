#!/bin/bash

# HTTPS 설정 스크립트 (Let's Encrypt 사용)
# 사용법: sudo ./setup-https.sh

set -e

DOMAIN="ceseats.r-e.kr"
NGINX_CONFIG="/etc/nginx/sites-available/default"
EMAIL="${SSL_EMAIL:-admin@${DOMAIN}}"

echo "=== HTTPS 설정 시작 ==="
echo "도메인: ${DOMAIN}"
echo ""

# 1. Certbot 설치 확인
echo "1. Certbot 설치 확인 중..."
if ! command -v certbot &> /dev/null; then
    echo "Certbot이 설치되지 않았습니다. 설치 중..."
    sudo apt-get update
    sudo apt-get install -y certbot python3-certbot-nginx
    echo "✅ Certbot 설치 완료"
else
    echo "✅ Certbot이 이미 설치되어 있습니다."
fi
echo ""

# 2. Nginx 설정 확인
echo "2. Nginx 설정 확인 중..."
if [ ! -f "${NGINX_CONFIG}" ]; then
    echo "❌ Nginx 설정 파일을 찾을 수 없습니다: ${NGINX_CONFIG}"
    exit 1
fi

# server_name이 도메인으로 설정되어 있는지 확인
if ! grep -q "server_name ${DOMAIN}" "${NGINX_CONFIG}"; then
    echo "⚠️  Nginx 설정에 server_name이 ${DOMAIN}으로 설정되어 있지 않습니다."
    echo "   현재 설정:"
    grep "server_name" "${NGINX_CONFIG}" || echo "   server_name이 없습니다."
    echo ""
    read -p "계속하시겠습니까? (y/n): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi
echo ""

# 3. 방화벽 확인 (80, 443 포트)
echo "3. 방화벽 확인 중..."
if command -v ufw &> /dev/null; then
    if sudo ufw status | grep -q "Status: active"; then
        echo "UFW 방화벽이 활성화되어 있습니다. 포트 확인 중..."
        sudo ufw allow 80/tcp
        sudo ufw allow 443/tcp
        echo "✅ 포트 80, 443 허용 완료"
    fi
elif command -v firewall-cmd &> /dev/null; then
    echo "firewalld 사용 중. 포트 확인 중..."
    sudo firewall-cmd --permanent --add-service=http
    sudo firewall-cmd --permanent --add-service=https
    sudo firewall-cmd --reload
    echo "✅ 포트 80, 443 허용 완료"
fi
echo ""

# 4. SSL 인증서 발급
echo "4. SSL 인증서 발급 중..."
echo "   이메일: ${EMAIL} (인증서 만료 알림용)"
echo ""

# Certbot으로 인증서 발급 및 Nginx 자동 설정
sudo certbot --nginx -d ${DOMAIN} --non-interactive --agree-tos --email ${EMAIL} || {
    echo "❌ SSL 인증서 발급 실패"
    echo "   확인 사항:"
    echo "   1. 도메인이 서버 IP로 올바르게 연결되어 있는지 확인"
    echo "   2. 포트 80이 열려있는지 확인"
    echo "   3. Nginx가 실행 중인지 확인"
    exit 1
}

echo "✅ SSL 인증서 발급 완료"
echo ""

# 5. 자동 갱신 설정
echo "5. 자동 갱신 설정 중..."
# Certbot이 자동으로 cron job을 설정하지만, 확인
if ! sudo crontab -l 2>/dev/null | grep -q "certbot renew"; then
    echo "자동 갱신 cron job 추가 중..."
    (sudo crontab -l 2>/dev/null; echo "0 0,12 * * * certbot renew --quiet") | sudo crontab -
    echo "✅ 자동 갱신 설정 완료"
else
    echo "✅ 자동 갱신이 이미 설정되어 있습니다."
fi
echo ""

# 6. Nginx 설정 확인
echo "6. Nginx 설정 확인 중..."
if sudo nginx -t; then
    echo "✅ Nginx 설정이 올바릅니다."
    sudo systemctl reload nginx
    echo "✅ Nginx 재시작 완료"
else
    echo "❌ Nginx 설정에 오류가 있습니다."
    exit 1
fi
echo ""

echo "=== HTTPS 설정 완료 ==="
echo "✅ https://${DOMAIN} 으로 접속 가능합니다."
echo ""
echo "인증서 정보:"
sudo certbot certificates
echo ""
echo "인증서는 90일마다 자동으로 갱신됩니다."

