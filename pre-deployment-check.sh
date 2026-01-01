#!/bin/bash

# 배포 전 확인 스크립트
# 사용법: ./pre-deployment-check.sh [gabia-user]@[gabia-host] [deploy-path] [ssh-key-path]

set -e

if [ $# -lt 2 ]; then
    echo "사용법: ./pre-deployment-check.sh [gabia-user]@[gabia-host] [deploy-path] [ssh-key-path]"
    echo "예시: ./pre-deployment-check.sh ubuntu@121.78.130.134 /opt/ceseats SSH_KeyPair-gabia-ces.pem"
    exit 1
fi

SERVER=$1
DEPLOY_PATH=$2
SSH_KEY=${3:-""}

# SSH 옵션 설정
if [ -n "${SSH_KEY}" ]; then
    # 절대 경로 또는 상대 경로 처리
    if [ -f "${SSH_KEY}" ]; then
        SSH_KEY_PATH="${SSH_KEY}"
    elif [ -f "${HOME}/Downloads/${SSH_KEY}" ]; then
        SSH_KEY_PATH="${HOME}/Downloads/${SSH_KEY}"
    elif [ -f "${HOME}/Downloads/$(basename ${SSH_KEY})" ]; then
        SSH_KEY_PATH="${HOME}/Downloads/$(basename ${SSH_KEY})"
    else
        echo "⚠️  SSH 키 파일을 찾을 수 없습니다: ${SSH_KEY}"
        echo "   다음 경로를 확인했습니다:"
        echo "   - ${SSH_KEY}"
        echo "   - ${HOME}/Downloads/${SSH_KEY}"
        echo "   - ${HOME}/Downloads/$(basename ${SSH_KEY})"
        SSH_OPTS="-o StrictHostKeyChecking=no"
        echo "   기본 SSH 키 사용 (실패할 수 있음)"
    fi
    
    if [ -n "${SSH_KEY_PATH}" ] && [ -f "${SSH_KEY_PATH}" ]; then
        SSH_OPTS="-i ${SSH_KEY_PATH} -o StrictHostKeyChecking=no"
        echo "SSH 키 파일 사용: ${SSH_KEY_PATH}"
        # 권한 확인
        PERM=$(stat -f "%A" "${SSH_KEY_PATH}" 2>/dev/null || stat -c "%a" "${SSH_KEY_PATH}" 2>/dev/null || echo "unknown")
        if [ "${PERM}" != "600" ] && [ "${PERM}" != "400" ]; then
            echo "⚠️  SSH 키 파일 권한이 안전하지 않습니다 (현재: ${PERM})"
            echo "   권장: chmod 400 ${SSH_KEY_PATH}"
        fi
    fi
else
    SSH_OPTS="-o StrictHostKeyChecking=no"
    echo "기본 SSH 키 사용"
fi

echo "=========================================="
echo "배포 전 확인 체크리스트"
echo "=========================================="
echo ""

# 1. SSH 접속 테스트
echo "1. SSH 접속 테스트..."
if ssh ${SSH_OPTS} -o ConnectTimeout=5 -o BatchMode=yes ${SERVER} "echo 'SSH 연결 성공'" 2>/dev/null; then
    echo "✅ SSH 접속 성공"
else
    echo "❌ SSH 접속 실패"
    echo "   - SSH 키가 올바르게 설정되었는지 확인"
    echo "   - 서버 주소와 사용자명이 올바른지 확인"
    exit 1
fi
echo ""

# 2. Docker 설치 확인
echo "2. Docker 설치 확인..."
if ssh ${SSH_OPTS} ${SERVER} "docker --version" > /dev/null 2>&1; then
    DOCKER_VERSION=$(ssh ${SSH_OPTS} ${SERVER} "docker --version")
    echo "✅ Docker 설치됨: ${DOCKER_VERSION}"
else
    echo "❌ Docker가 설치되지 않았습니다"
    echo "   설치 명령: curl -fsSL https://get.docker.com -o get-docker.sh && sudo sh get-docker.sh"
    exit 1
fi
echo ""

# 3. Docker 실행 권한 확인
echo "3. Docker 실행 권한 확인..."
if ssh ${SSH_OPTS} ${SERVER} "docker ps" > /dev/null 2>&1; then
    echo "✅ Docker 실행 권한 있음"
else
    echo "⚠️  Docker 실행 권한 없음 (sudo 필요할 수 있음)"
    echo "   해결: sudo usermod -aG docker \$USER"
fi
echo ""

# 4. 배포 경로 확인
echo "4. 배포 경로 확인..."
if ssh ${SSH_OPTS} ${SERVER} "test -d ${DEPLOY_PATH}" 2>/dev/null; then
    echo "✅ 배포 경로 존재: ${DEPLOY_PATH}"
    PERM=$(ssh ${SSH_OPTS} ${SERVER} "stat -c '%a' ${DEPLOY_PATH}" 2>/dev/null || ssh ${SSH_OPTS} ${SERVER} "stat -f '%A' ${DEPLOY_PATH}" 2>/dev/null)
    echo "   권한: ${PERM}"
else
    echo "⚠️  배포 경로가 없습니다. 생성 중..."
    ssh ${SSH_OPTS} ${SERVER} "sudo mkdir -p ${DEPLOY_PATH} && sudo chmod 755 ${DEPLOY_PATH}"
    echo "✅ 배포 경로 생성 완료"
fi
echo ""

# 5. 포트 사용 가능 확인
echo "5. 포트 사용 가능 확인..."
if ssh ${SSH_OPTS} ${SERVER} "netstat -tuln 2>/dev/null | grep -q ':8080 ' || ss -tuln 2>/dev/null | grep -q ':8080 '" 2>/dev/null; then
    echo "⚠️  포트 8080이 사용 중입니다"
else
    echo "✅ 포트 8080 사용 가능"
fi

if ssh ${SSH_OPTS} ${SERVER} "netstat -tuln 2>/dev/null | grep -q ':8081 ' || ss -tuln 2>/dev/null | grep -q ':8081 '" 2>/dev/null; then
    echo "⚠️  포트 8081이 사용 중입니다"
else
    echo "✅ 포트 8081 사용 가능"
fi
echo ""

# 6. Docker 네트워크 확인
echo "6. Docker 네트워크 확인..."
if ssh ${SSH_OPTS} ${SERVER} "docker network ls | grep -q ceseats-network" 2>/dev/null; then
    echo "✅ Docker 네트워크 'ceseats-network' 존재"
else
    echo "⚠️  Docker 네트워크 'ceseats-network' 없음 (배포 시 자동 생성됨)"
fi
echo ""

# 7. 기존 컨테이너 확인
echo "7. 기존 컨테이너 확인..."
BLUE_EXISTS=$(ssh ${SSH_OPTS} ${SERVER} "docker ps -a --format '{{.Names}}' | grep -q '^ceseats-blue$' && echo 'yes' || echo 'no'")
GREEN_EXISTS=$(ssh ${SSH_OPTS} ${SERVER} "docker ps -a --format '{{.Names}}' | grep -q '^ceseats-green$' && echo 'yes' || echo 'no'")

if [ "${BLUE_EXISTS}" = "yes" ]; then
    echo "⚠️  기존 'ceseats-blue' 컨테이너 존재"
    ssh ${SSH_OPTS} ${SERVER} "docker ps -a | grep ceseats-blue"
else
    echo "✅ 'ceseats-blue' 컨테이너 없음 (첫 배포)"
fi

if [ "${GREEN_EXISTS}" = "yes" ]; then
    echo "⚠️  기존 'ceseats-green' 컨테이너 존재"
    ssh ${SSH_OPTS} ${SERVER} "docker ps -a | grep ceseats-green"
else
    echo "✅ 'ceseats-green' 컨테이너 없음 (첫 배포)"
fi
echo ""

# 8. 디스크 공간 확인
echo "8. 디스크 공간 확인..."
DISK_USAGE=$(ssh ${SSH_OPTS} ${SERVER} "df -h ${DEPLOY_PATH} | tail -1 | awk '{print \$5}' | sed 's/%//'")
if [ "${DISK_USAGE}" -lt 80 ]; then
    echo "✅ 디스크 공간 충분: ${DISK_USAGE}% 사용 중"
else
    echo "⚠️  디스크 공간 부족: ${DISK_USAGE}% 사용 중"
fi
echo ""

# 9. 방화벽 확인 (선택사항)
echo "9. 방화벽 확인..."
if ssh ${SSH_OPTS} ${SERVER} "command -v ufw > /dev/null 2>&1" 2>/dev/null; then
    UFW_STATUS=$(ssh ${SSH_OPTS} ${SERVER} "sudo ufw status | head -1" 2>/dev/null || echo "비활성")
    echo "   UFW 상태: ${UFW_STATUS}"
    echo "   포트 8080, 8081이 열려있는지 확인 필요"
elif ssh ${SSH_OPTS} ${SERVER} "command -v firewall-cmd > /dev/null 2>&1" 2>/dev/null; then
    echo "   firewalld 사용 중"
    echo "   포트 8080, 8081이 열려있는지 확인 필요"
else
    echo "   방화벽 도구를 찾을 수 없음 (클라우드 방화벽 설정 확인 필요)"
fi
echo ""

# 10. 환경 변수 확인 (선택사항)
echo "10. 환경 변수 확인..."
echo "   GOOGLE_PLACES_API_KEY: ${GOOGLE_PLACES_API_KEY:+설정됨} ${GOOGLE_PLACES_API_KEY:-⚠️  설정되지 않음}"
echo "   OPENAI_API_KEY: ${OPENAI_API_KEY:+설정됨} ${OPENAI_API_KEY:-⚠️  설정되지 않음 (선택사항)}"
echo ""

echo "=========================================="
echo "✅ 사전 확인 완료!"
echo "=========================================="
echo ""
echo "다음 단계:"
echo "1. GitHub에 코드 푸시 (main 브랜치)"
echo "2. GitHub Actions에서 워크플로우 실행 확인"
echo "3. 배포 완료 후 헬스 체크: curl http://${SERVER#*@}/api/health"
echo ""

