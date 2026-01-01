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
NGINX_SITES_AVAILABLE="/etc/nginx/sites-available/default"

# 배포 경로 및 파일 권한 확인
if [ ! -d "${DEPLOY_PATH}" ]; then
    echo "배포 경로 생성 중: ${DEPLOY_PATH}"
    mkdir -p "${DEPLOY_PATH}"
fi

# .active-port 파일이 없으면 생성 (권한 확인)
if [ ! -f "${ACTIVE_PORT_FILE}" ]; then
    touch "${ACTIVE_PORT_FILE}" 2>/dev/null || {
        echo "⚠️  .active-port 파일 생성 권한이 없습니다. sudo를 사용하거나 디렉토리 권한을 확인하세요."
        echo "   해결 방법: sudo chown -R \$(whoami) ${DEPLOY_PATH}"
    }
fi

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

# 3-1. PostgreSQL 컨테이너 확인 및 시작
echo "3-1. PostgreSQL 컨테이너 확인 중..."
POSTGRES_DATA_DIR="${POSTGRES_DATA_DIR:-/mnt/blockstorage/postgresql}"

# 블록 스토리지 디렉토리 생성 및 권한 설정
if [ ! -d "${POSTGRES_DATA_DIR}" ]; then
    echo "블록 스토리지 디렉토리 생성 중: ${POSTGRES_DATA_DIR}"
    sudo mkdir -p ${POSTGRES_DATA_DIR}
    sudo chown 999:999 ${POSTGRES_DATA_DIR}  # PostgreSQL 컨테이너의 postgres 사용자 UID/GID
    sudo chmod 700 ${POSTGRES_DATA_DIR}
fi

if ! docker ps | grep -q ceseats-postgres; then
    if docker ps -a | grep -q ceseats-postgres; then
        echo "기존 PostgreSQL 컨테이너 시작 중..."
        docker start ceseats-postgres
    else
        echo "PostgreSQL 컨테이너 생성 중..."
        echo "데이터 저장 경로: ${POSTGRES_DATA_DIR}"
        docker run -d \
            --name ceseats-postgres \
            -e POSTGRES_DB=ceseats \
            -e POSTGRES_USER=ceseats \
            -e POSTGRES_PASSWORD=${DATABASE_PASSWORD:-ceseats} \
            -p 5432:5432 \
            --restart unless-stopped \
            --network ceseats-network \
            -v ${POSTGRES_DATA_DIR}:/var/lib/postgresql/data \
            postgres:15-alpine
        echo "PostgreSQL 초기화 대기 중..."
        sleep 10
    fi
else
    echo "✅ PostgreSQL 컨테이너 실행 중"
fi

# 4. 새 컨테이너 실행
echo "4. 새 ${DEPLOY_TO} 컨테이너 시작 중..."
docker run -d \
    --name ${DEPLOY_SERVICE} \
    -p ${DEPLOY_PORT}:8080 \
    -e GOOGLE_PLACES_API_KEY="${GOOGLE_PLACES_API_KEY}" \
    -e OPENAI_API_KEY="${OPENAI_API_KEY}" \
    -e DATABASE_URL="jdbc:postgresql://ceseats-postgres:5432/ceseats" \
    -e DATABASE_USERNAME="${DATABASE_USERNAME:-ceseats}" \
    -e DATABASE_PASSWORD="${DATABASE_PASSWORD:-ceseats}" \
    --restart unless-stopped \
    --network ceseats-network \
    ${IMAGE_NAME}

# 5. 헬스 체크
echo "5. 헬스 체크 중..."
MAX_RETRIES=60
RETRY_COUNT=0
HEALTH_CHECK_URL="http://localhost:${DEPLOY_PORT}/api/health"

# 컨테이너가 실행 중인지 확인
echo "컨테이너 상태 확인 중..."
sleep 5
if ! docker ps | grep -q ${DEPLOY_SERVICE}; then
    echo "❌ 컨테이너가 실행되지 않았습니다"
    docker logs ${DEPLOY_SERVICE} 2>/dev/null || echo "로그를 가져올 수 없습니다"
    exit 1
fi

echo "헬스 체크 시작 (최대 ${MAX_RETRIES}회 시도, 각 3초 간격)..."
while [ ${RETRY_COUNT} -lt ${MAX_RETRIES} ]; do
    # curl로 헬스 체크 시도
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 ${HEALTH_CHECK_URL} 2>/dev/null || echo "000")
    
    if [ "${HTTP_CODE}" = "200" ]; then
        echo "✅ ${DEPLOY_TO} 환경 헬스 체크 성공 (HTTP ${HTTP_CODE})"
        break
    fi
    
    RETRY_COUNT=$((RETRY_COUNT + 1))
    if [ $((RETRY_COUNT % 5)) -eq 0 ]; then
        echo "재시도 중... (${RETRY_COUNT}/${MAX_RETRIES}) - HTTP 코드: ${HTTP_CODE}"
        # 중간에 컨테이너 로그 확인
        echo "--- 컨테이너 로그 (최근 20줄) ---"
        docker logs --tail 20 ${DEPLOY_SERVICE} 2>/dev/null || echo "로그를 가져올 수 없습니다"
        echo "--- 컨테이너 상태 ---"
        docker ps | grep ${DEPLOY_SERVICE} || echo "컨테이너가 실행되지 않음"
    fi
    sleep 3
done

if [ ${RETRY_COUNT} -eq ${MAX_RETRIES} ]; then
    echo "❌ ${DEPLOY_TO} 환경 헬스 체크 실패 (${MAX_RETRIES}회 시도 후)"
    echo "--- 최종 컨테이너 로그 ---"
    docker logs --tail 50 ${DEPLOY_SERVICE} 2>/dev/null || echo "로그를 가져올 수 없습니다"
    echo "--- 컨테이너 상태 ---"
    docker ps -a | grep ${DEPLOY_SERVICE} || echo "컨테이너를 찾을 수 없음"
    echo "--- 포트 확인 ---"
    (netstat -tuln 2>/dev/null | grep ${DEPLOY_PORT} || ss -tuln 2>/dev/null | grep ${DEPLOY_PORT} || echo "포트 ${DEPLOY_PORT}가 열려있지 않음") || true
    docker stop ${DEPLOY_SERVICE} 2>/dev/null || true
    docker rm ${DEPLOY_SERVICE} 2>/dev/null || true
    exit 1
fi

# 6. Nginx 설정 업데이트 및 트래픽 전환
echo "6. 트래픽 전환 중..."

# 6-1. 서버에 직접 설치된 Nginx 설정 업데이트 (우선)
if [ -f "${NGINX_SITES_AVAILABLE}" ]; then
    echo "서버 Nginx 설정 파일 업데이트 중: ${NGINX_SITES_AVAILABLE}"
    
    # 백업 생성
    sudo cp ${NGINX_SITES_AVAILABLE} ${NGINX_SITES_AVAILABLE}.bak
    
    # location / 블록 내의 proxy_pass만 찾아서 변경
    # sed의 범위 주소를 사용하여 location / { ... } 블록 내에서만 변경
    # location / { 부터 해당 블록의 } 까지 범위 지정
    # -E 옵션으로 확장 정규식 사용, 더 정확한 매칭
    sudo sed -i.tmp -E "/location\s+\/\s*\{/,/^\s*\}/ s|(proxy_pass\s+http://127\.0\.0\.1:)[0-9]+(;)|\1${DEPLOY_PORT}\2|" ${NGINX_SITES_AVAILABLE} 2>/dev/null || {
        # 위 방법이 실패하면 더 간단한 방법 사용
        # location / 블록 내에서만 찾기 (중괄호 범위 사용)
        sudo sed -i.tmp "/^\s*location\s\+\/\s*{/,/^\s*}/ s|proxy_pass http://127\.0\.0\.1:[0-9]*;|proxy_pass http://127.0.0.1:${DEPLOY_PORT};|" ${NGINX_SITES_AVAILABLE} 2>/dev/null || {
            # 최후의 수단: 모든 proxy_pass 변경 (다른 location 블록이 없는 경우)
            sudo sed -i.tmp "s|proxy_pass http://127\.0\.0\.1:[0-9]*;|proxy_pass http://127.0.0.1:${DEPLOY_PORT};|" ${NGINX_SITES_AVAILABLE}
        }
    }
    sudo rm -f ${NGINX_SITES_AVAILABLE}.tmp 2>/dev/null || true
    
    # 변경 확인 (디버깅용)
    echo "변경된 proxy_pass 확인:"
    sudo grep -n "proxy_pass" ${NGINX_SITES_AVAILABLE} | head -5 || true
    
    # Nginx 설정 테스트
    if sudo nginx -t 2>/dev/null; then
        sudo systemctl reload nginx 2>/dev/null || sudo service nginx reload 2>/dev/null
        echo "✅ 서버 Nginx 설정 업데이트 완료 (트래픽 전환: 포트 ${DEPLOY_PORT})"
    else
        echo "⚠️  Nginx 설정 테스트 실패. 변경사항을 롤백합니다."
        sudo mv ${NGINX_SITES_AVAILABLE}.bak ${NGINX_SITES_AVAILABLE} 2>/dev/null || true
        exit 1
    fi
else
    echo "⚠️  서버 Nginx 설정 파일을 찾을 수 없습니다: ${NGINX_SITES_AVAILABLE}"
fi

# 6-2. Docker Nginx 컨테이너 확인 및 생성/업데이트 (백업)
if ! docker ps | grep -q ceseats-nginx; then
    # Nginx 컨테이너가 없으면 생성
    if [ -f "${NGINX_CONF}" ]; then
        echo "Docker Nginx 컨테이너 생성 중..."
        docker run -d \
            --name ceseats-nginx \
            -p 80:80 \
            --restart unless-stopped \
            --network ceseats-network \
            -v ${NGINX_CONF}:/etc/nginx/nginx.conf:ro \
            nginx:alpine
        echo "✅ Docker Nginx 컨테이너 생성 완료"
        sleep 2
    else
        echo "⚠️  Docker Nginx 설정 파일이 없습니다: ${NGINX_CONF}"
    fi
fi

# Docker Nginx 설정 업데이트 (있는 경우만)
if docker ps | grep -q ceseats-nginx; then
    if [ -f "${NGINX_CONF}" ]; then
        # nginx.conf에서 upstream 서버 변경
        sed -i.bak "s/server ceseats-.*:8080;/server ${NGINX_UPSTREAM};/" ${NGINX_CONF}
        docker exec ceseats-nginx nginx -s reload
        echo "✅ Docker Nginx 설정 업데이트 완료 (트래픽 전환: ${NGINX_UPSTREAM})"
    fi
fi

# 7. 활성 포트 파일 업데이트
echo "${DEPLOY_TO}" > "${ACTIVE_PORT_FILE}" 2>/dev/null || {
    echo "⚠️  .active-port 파일 쓰기 권한이 없습니다."
    echo "   해결 방법: sudo chown -R \$(whoami) ${DEPLOY_PATH} 또는 sudo chmod 777 ${DEPLOY_PATH}"
    echo "   또는 수동으로 다음 명령 실행: echo '${DEPLOY_TO}' | sudo tee ${ACTIVE_PORT_FILE}"
    # 권한 오류가 있어도 배포는 성공한 것으로 간주
}

# 8. 기존 활성 애플리케이션 컨테이너 정리 (트래픽 전환 완료 후)
# 참고: PostgreSQL 컨테이너(ceseats-postgres)는 데이터베이스이므로 정리하지 않음
echo "8. 기존 활성 애플리케이션 컨테이너 정리 중..."
if docker ps | grep -q "${CURRENT_SERVICE}"; then
    echo "기존 ${CURRENT_SERVICE} 컨테이너 중지 및 제거 중..."
    docker stop ${CURRENT_SERVICE} 2>/dev/null || true
    docker rm ${CURRENT_SERVICE} 2>/dev/null || true
    echo "✅ 기존 ${CURRENT_SERVICE} 컨테이너 정리 완료"
else
    echo "기존 ${CURRENT_SERVICE} 컨테이너가 없습니다."
fi

echo "✅ Blue-Green 배포 완료!"
echo "활성 환경: ${DEPLOY_TO} (포트: ${DEPLOY_PORT})"
echo ""
echo "현재 실행 중인 컨테이너:"
docker ps | grep ceseats

