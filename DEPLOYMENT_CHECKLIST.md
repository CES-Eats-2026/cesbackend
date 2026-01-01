# 배포 체크리스트

## 배포 전 확인사항

### 1. 환경 변수 확인
```bash
# 필수 환경 변수
GOOGLE_PLACES_API_KEY=your_key_here
DATABASE_PASSWORD=your_password_here

# 선택사항
OPENAI_API_KEY=your_key_here (CES 이유 생성용)
CORS_ALLOWED_ORIGINS=https://cesfront.vercel.app

# 참고: DATABASE_URL과 DATABASE_USERNAME은 스크립트에서 자동 설정됨
# - DATABASE_URL: jdbc:postgresql://ceseats-postgres:5432/ceseats (같은 Docker 네트워크)
# - DATABASE_USERNAME: ceseats (기본값)
```

### 2. 데이터베이스 백업 (기존 데이터가 있는 경우)
```bash
# PostgreSQL 백업
docker exec ceseats-postgres pg_dump -U ceseats ceseats > backup_$(date +%Y%m%d_%H%M%S).sql
```

### 3. 배포 스크립트 실행
```bash
# 1. 사전 확인
./pre-deployment-check.sh [user]@[host] [deploy-path] [ssh-key]

# 2. Blue-Green 배포
./blue-green-deploy.sh [blue|green]
```

## 배포 후 확인사항

### 1. 헬스 체크
```bash
curl http://your-server/api/health
```

### 2. 데이터베이스 스키마 확인
```bash
# PostgreSQL에 접속하여 테이블 구조 확인
docker exec -it ceseats-postgres psql -U ceseats -d ceseats

# PlaceView 테이블 구조 확인
\d place_views

# 새 컬럼 확인
SELECT column_name, data_type, is_nullable 
FROM information_schema.columns 
WHERE table_name = 'place_views';
```

### 3. 스케줄러 동작 확인
```bash
# 애플리케이션 로그에서 스케줄러 실행 확인
docker logs ceseats-blue | grep "Updated 10-minute snapshots"
```

### 4. API 동작 확인
```bash
# 추천 API 테스트
curl -X POST http://your-server/api/recommendations \
  -H "Content-Type: application/json" \
  -d '{
    "latitude": 36.1147,
    "longitude": -115.1728,
    "timeOption": "30",
    "type": "all"
  }'

# 조회수 증가 API 테스트
curl -X POST http://your-server/api/places/{placeId}/view
```

### 5. 실시간 조회수 급상승 기능 확인
- 프론트엔드에서 장소 클릭 시 조회수 증가 확인
- "실시간 조회수 급상승" 컴포넌트에서 증가량 표시 확인
- 10분 후 스케줄러가 스냅샷 업데이트하는지 확인

## 롤백 방법

### 문제 발생 시 롤백
```bash
# 이전 버전으로 전환
./blue-green-deploy.sh [blue|green]  # 현재 활성과 반대 환경으로 배포
```

### 데이터베이스 롤백 (필요한 경우)
```bash
# 백업 파일로 복원
docker exec -i ceseats-postgres psql -U ceseats ceseats < backup_YYYYMMDD_HHMMSS.sql
```

## 주의사항

1. **데이터 손실 방지:** 배포 전 반드시 데이터베이스 백업
2. **환경 변수 확인:** 프로덕션 환경 변수가 올바르게 설정되었는지 확인
3. **점진적 배포:** Blue-Green 배포 방식으로 무중단 배포 권장
4. **모니터링:** 배포 후 최소 10분간 로그 모니터링 권장

