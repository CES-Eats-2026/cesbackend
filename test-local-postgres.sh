#!/bin/bash

# 로컬 PostgreSQL 테스트 스크립트

set -e

echo "=========================================="
echo "로컬 PostgreSQL 테스트"
echo "=========================================="
echo ""

# 1. PostgreSQL 컨테이너 시작
echo "1. PostgreSQL 컨테이너 시작 중..."
docker-compose -f docker-compose.local.yml up -d postgres

# 2. PostgreSQL 준비 대기
echo "2. PostgreSQL 준비 대기 중..."
MAX_WAIT=30
WAIT_COUNT=0

while [ ${WAIT_COUNT} -lt ${MAX_WAIT} ]; do
    if docker exec ceseats-postgres-local pg_isready -U ceseats > /dev/null 2>&1; then
        echo "✅ PostgreSQL 준비 완료"
        break
    fi
    WAIT_COUNT=$((WAIT_COUNT + 1))
    echo "대기 중... (${WAIT_COUNT}/${MAX_WAIT})"
    sleep 1
done

if [ ${WAIT_COUNT} -eq ${MAX_WAIT} ]; then
    echo "❌ PostgreSQL 준비 실패"
    docker logs ceseats-postgres-local
    exit 1
fi

# 3. 데이터베이스 연결 테스트
echo "3. 데이터베이스 연결 테스트..."
docker exec ceseats-postgres-local psql -U ceseats -d ceseats -c "SELECT version();"

# 4. 애플리케이션 실행 (선택사항)
echo ""
echo "=========================================="
echo "✅ PostgreSQL 준비 완료!"
echo "=========================================="
echo ""
echo "다음 명령어로 애플리케이션을 실행하세요:"
echo ""
echo "export DATABASE_URL=jdbc:postgresql://localhost:5432/ceseats"
echo "export DATABASE_USERNAME=ceseats"
echo "export DATABASE_PASSWORD=ceseats"
echo "export SPRING_PROFILES_ACTIVE=local"
echo "./mvnw spring-boot:run"
echo ""
echo "또는 application-local.properties를 사용하세요."
echo ""
echo "PostgreSQL 중지: docker-compose -f docker-compose.local.yml down"
echo ""

