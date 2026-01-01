#!/bin/bash

# Blue-Green 배포 스크립트
# 사용법: ./blue-green-deploy.sh [blue|green]

set -e

DEPLOY_PATH="${GABIA_DEPLOY_PATH:-/opt/ceseats}"
IMAGE_NAME="${DOCKER_USERNAME}/ces-eats-backend:latest"
BLUE_SERVICE="ceseats-blue"
GREEN_SERVICE="ceseats-green"
PORT_BLUE=8080
PORT_GREEN=8081
ACTIVE_PORT_FILE="${DEPLOY_PATH}/.active-port"
NGINX_CONF="${DEPLOY_PATH}/nginx.conf"

# 배포 대상 결정
if [ "$1" = "green" ]; then
    DEPLOY_TO="green"
    DEPLOY_PORT=${PORT_GREEN}
    DEPLOY_SERVICE=${GREEN_SERVICE}
    CURRENT_SERVICE=${BLUE_SERVICE}
    NGINX_UPSTREAM="ceseats-green:8080"
elif [ "$1" = "blue" ]; then
    DEPLOY_TO="blue"
    DEPLOY_PORT=${PORT_BLUE}
    DEPLOY_SERVICE=${BLUE_SERVICE}
    CURRENT_SERVICE=${GREEN_SERVICE}
    NGINX_UPSTREAM="ceseats-blue:8080"
else
    # 자동 결정: 현재 활성과 반대
    if [ -f "${ACTIVE_PORT_FILE}" ]; then
        CURRENT_ACTIVE=$(cat "${ACTIVE_PORT_FILE}")
    else
        CURRENT_ACTIVE="blue"
    fi
    
    if [ "${CURRENT_ACTIVE}" = "blue" ]; then
        DEPLOY_TO="green"
        DEPLOY_PORT=${PORT_GREEN}
        DEPLOY_SERVICE=${GREEN_SERVICE}
        CURRENT_SERVICE=${BLUE_SERVICE}
        NGINX_UPSTREAM="ceseats-green:8080"
    else
        DEPLOY_TO="blue"
        DEPLOY_PORT=${PORT_BLUE}
        DEPLOY_SERVICE=${BLUE_SERVICE}
        CURRENT_SERVICE=${GREEN_SERVICE}
        NGINX_UPSTREAM="ceseats-blue:8080"
    fi
fi

echo "=== Blue-Green 배포 시작 ==="
echo "배포 대상: ${DEPLOY_TO} (포트: ${DEPLOY_PORT})"

# 1. Docker 이미지 pull
echo "1. Docker 이미지 다운로드 중..."
docker pull ${IMAGE_NAME}

# 2. 기존 컨테이너 중지 및 제거
echo "2. 기존 ${DEPLOY_TO} 컨테이너 정리 중..."
docker stop ${DEPLOY_SERVICE} 2>/dev/null || true
docker rm ${DEPLOY_SERVICE} 2>/dev/null || true

# 3. Docker 네트워크 생성 (없는 경우)
echo "3. Docker 네트워크 확인 중..."
docker network create ceseats-network 2>/dev/null || true

# 4. 새 컨테이너 실행
echo "4. 새 ${DEPLOY_TO} 컨테이너 시작 중..."
docker run -d \
    --name ${DEPLOY_SERVICE} \
    -p ${DEPLOY_PORT}:8080 \
    -e GOOGLE_PLACES_API_KEY="${GOOGLE_PLACES_API_KEY}" \
    -e OPENAI_API_KEY="${OPENAI_API_KEY}" \
    --restart unless-stopped \
    --network ceseats-network \
    ${IMAGE_NAME}

# 5. 헬스 체크
echo "5. 헬스 체크 중..."
MAX_RETRIES=30
RETRY_COUNT=0
HEALTH_CHECK_URL="http://localhost:${DEPLOY_PORT}/api/health"

while [ ${RETRY_COUNT} -lt ${MAX_RETRIES} ]; do
    if curl -f ${HEALTH_CHECK_URL} > /dev/null 2>&1; then
        echo "✅ ${DEPLOY_TO} 환경 헬스 체크 성공"
        break
    fi
    RETRY_COUNT=$((RETRY_COUNT + 1))
    echo "재시도 중... (${RETRY_COUNT}/${MAX_RETRIES})"
    sleep 2
done

if [ ${RETRY_COUNT} -eq ${MAX_RETRIES} ]; then
    echo "❌ ${DEPLOY_TO} 환경 헬스 체크 실패"
    docker logs ${DEPLOY_SERVICE}
    docker stop ${DEPLOY_SERVICE}
    docker rm ${DEPLOY_SERVICE}
    exit 1
fi

# 6. Nginx 설정 업데이트 (Nginx가 있는 경우)
echo "6. 트래픽 전환 중..."
if docker ps | grep -q ceseats-nginx; then
    if [ -f "${NGINX_CONF}" ]; then
        # nginx.conf에서 upstream 서버 변경
        sed -i.bak "s/server ceseats-.*:8080;/server ${NGINX_UPSTREAM};/" ${NGINX_CONF}
        docker exec ceseats-nginx nginx -s reload
        echo "✅ Nginx 설정 업데이트 완료"
    fi
else
    echo "⚠️  Nginx 컨테이너가 없습니다. 트래픽 전환은 수동으로 설정해야 합니다."
fi

# 7. 활성 포트 파일 업데이트
echo "${DEPLOY_TO}" > "${ACTIVE_PORT_FILE}"

echo "✅ Blue-Green 배포 완료!"
echo "활성 환경: ${DEPLOY_TO} (포트: ${DEPLOY_PORT})"
docker ps | grep ceseats

