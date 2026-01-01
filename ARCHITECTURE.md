# CESEats Backend 아키텍처

## 폴더 구조

```
backend/src/main/java/com/ceseats/
├── CesEatsApplication.java          # Spring Boot 메인 애플리케이션
├── controller/
│   └── PlaceController.java          # REST API 컨트롤러
├── dto/
│   ├── request/
│   │   └── PlaceSearchRequest.java    # 검색 요청 DTO
│   └── response/
│       ├── PlaceResponse.java         # 정규화된 장소 응답 DTO
│       └── PlaceSearchResponse.java   # 검색 응답 DTO
├── entity/
│   └── PlaceView.java                 # 조회수 추적 Entity
├── repository/
│   └── PlaceViewRepository.java      # PlaceView JPA Repository
└── service/
    ├── PlaceService.java              # 메인 비즈니스 로직 서비스
    ├── google/
    │   ├── GooglePlacesClient.java    # Google Places API 클라이언트
    │   └── PlaceDetails.java          # Place Details 데이터 모델
    ├── cache/
    │   └── CacheService.java          # 캐싱 서비스 (인메모리)
    └── hook/
        └── HookMessageService.java    # Hook 메시지 생성 서비스
```

## 주요 컴포넌트

### 1. PlaceController
- REST API 엔드포인트 제공
- 요청/응답 변환
- 조회수 증가 API

### 2. PlaceService
- 메인 비즈니스 로직
- Google Places API 호출 조율
- 캐싱 관리
- 데이터 정규화
- 정렬 처리
- 도보 시간 계산

### 3. GooglePlacesClient
- Google Places Nearby Search API 호출
- Google Places Place Details API 호출
- JSON 파싱 및 데이터 변환

### 4. CacheService
- 인메모리 캐싱 (MVP 레벨)
- Nearby Search 결과: 10분 TTL
- Place Details: 1시간 TTL
- Redis 스타일 추상화 (프로덕션에서 Redis로 교체 가능)

### 5. HookMessageService
- 가격 비교 기반 Hook 메시지 생성
- 주변 장소 평균 가격 계산
- 마케팅 스타일 메시지 생성

### 6. PlaceView Entity
- 조회수 추적
- JPA를 통한 영속성 관리

## 데이터 흐름

```
1. 클라이언트 요청
   ↓
2. PlaceController (요청 검증)
   ↓
3. PlaceService (비즈니스 로직)
   ├─→ CacheService (캐시 확인)
   ├─→ GooglePlacesClient (API 호출)
   ├─→ HookMessageService (Hook 메시지 생성)
   └─→ PlaceViewRepository (조회수 조회/업데이트)
   ↓
4. PlaceResponse 변환
   ↓
5. 클라이언트 응답
```

## 캐싱 전략

### Nearby Search 캐시
- **키**: `nearby:{latitude}:{longitude}:{radius}`
- **TTL**: 10분
- **용도**: 반복적인 위치 검색 비용 절감

### Place Details 캐시
- **키**: `place_id`
- **TTL**: 1시간
- **용도**: 동일 장소 상세 정보 반복 조회 비용 절감

## 정렬 옵션

1. **price_asc**: 가격 오름차순
   - `priceLevel` 기준 정렬
   - "$" < "$$" < "$$$"

2. **view_desc**: 조회수 내림차순
   - `viewCount` 기준 정렬
   - 가장 많이 본 장소 우선

## Hook 메시지 생성 로직

1. 주변 장소들의 평균 가격 수준 계산
2. 타겟 장소 가격과 비교
3. 가격 차이에 따라 메시지 생성:
   - **저렴한 경우** (차이 > 0.5):
     - 30% 이상: "여기는 {아이템}가 주변보다 {퍼센트}% 저렴해요! 가성비 최고예요."
     - 15-30%: "여기는 {아이템}가 주변보다 {퍼센트}% 저렴해요!"
     - 15% 미만: "여기는 {아이템}가 주변보다 조금 저렴해요."
   - **비싼 경우** (차이 < -0.5):
     - 30% 이상: "여기는 {아이템}가 주변보다 {퍼센트}% 비싸지만, 퀄리티가 확실해요."
     - 30% 미만: "여기는 {아이템}가 주변보다 조금 비싸지만, 가치가 있어요."
   - **비슷한 경우** (차이 -0.5 ~ 0.5):
     - 평점 4.5 이상: "여기는 {아이템}가 주변 평균 가격이지만, 평점이 높아요!"
     - 리뷰 100개 이상: "여기는 {아이템}가 주변 평균 가격이고, 리뷰가 많아요!"
     - 기본: "여기는 {아이템}가 주변 평균 가격대예요."

## 기술 스택

- **Spring Boot 3.2.0**: 웹 프레임워크
- **Spring Data JPA**: 데이터베이스 접근
- **H2 Database**: 인메모리 데이터베이스 (MVP)
- **Jackson**: JSON 처리
- **Lombok**: 보일러플레이트 코드 제거

## 확장 가능성

### 프로덕션 개선 사항

1. **캐싱**: 인메모리 캐시 → Redis
2. **데이터베이스**: H2 → PostgreSQL/MySQL
3. **비동기 처리**: 비동기 API 호출로 응답 시간 개선
4. **에러 처리**: 상세한 에러 응답 및 로깅
5. **인증/인가**: JWT 기반 인증
6. **모니터링**: Prometheus, Grafana 통합
7. **로깅**: ELK 스택 통합

### 기능 확장

1. **Popular Times**: Google Places Popular Times API 통합
2. **실시간 혼잡도**: 실시간 데이터 수집
3. **개인화 추천**: 사용자 선호도 기반 추천
4. **리뷰 분석**: 감정 분석을 통한 리뷰 요약

