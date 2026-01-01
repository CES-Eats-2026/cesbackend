#!/bin/bash

# CESEats Backend 배포 스크립트

echo "=== CESEats Backend 배포 시작 ==="

# 1. 빌드
echo "1. JAR 파일 빌드 중..."
./mvnw clean package -DskipTests

if [ $? -ne 0 ]; then
    echo "❌ 빌드 실패"
    exit 1
fi

echo "✅ 빌드 완료"

# 2. 서버 정보 입력
read -p "서버 주소를 입력하세요 (예: username@gabia-server): " SERVER
read -p "서버 경로를 입력하세요 (예: /home/username/app): " SERVER_PATH

# 3. 파일 업로드
echo "2. 서버에 파일 업로드 중..."
scp target/ces-eats-backend-0.0.1-SNAPSHOT.jar ${SERVER}:${SERVER_PATH}/

if [ $? -ne 0 ]; then
    echo "❌ 업로드 실패"
    exit 1
fi

echo "✅ 업로드 완료"

# 4. 서버에서 재시작
echo "3. 서버에서 애플리케이션 재시작 중..."
ssh ${SERVER} << EOF
cd ${SERVER_PATH}
pkill -f ces-eats-backend || true
sleep 2
nohup java -jar -Dspring.profiles.active=prod ces-eats-backend-0.0.1-SNAPSHOT.jar > app.log 2>&1 &
sleep 3
ps aux | grep ces-eats-backend | grep -v grep
EOF

echo "✅ 배포 완료!"
echo "로그 확인: ssh ${SERVER} 'tail -f ${SERVER_PATH}/app.log'"

