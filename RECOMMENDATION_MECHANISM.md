# CESEats 추천 메커니즘

## 개요

CESEats는 Google Places API를 활용하여 사용자 위치 기반으로 주변 레스토랑과 카페를 실시간으로 검색하고 추천하는 시스템입니다. 사용자가 선택한 시간 옵션과 타입 필터를 기반으로 최적의 장소를 추천합니다.

## 전체 흐름도

```
[프론트엔드 요청]
    ↓
[RecommendationController]
    ↓
[RecommendationService]
    ├─ timeOption → 반경(미터) 변환
    ├─ PlaceSearchRequest 생성
    └─ PlaceService 호출
        ↓
[PlaceService]
    ├─ 1. 캐시 확인 (Nearby Search)
    ├─ 2. Google Places Nearby Search API 호출 (캐시 미스 시)
    ├─ 3. 각 place_id에 대해 Place Details 가져오기
    │   ├─ 캐시 확인
    │   └─ Google Places Place Details API 호출 (캐시 미스 시)
    ├─ 4. 도보 시간 계산 (Haversine 공식)
    ├─ 5. 조회수 조회
    ├─ 6. Hook 메시지 생성
    │   └─ HookMessageService
    │       ├─ 주변 장소 평균 가격 계산
    │       └─ 가격 비교 기반 메시지 생성
    ├─ 7. 정렬 (price_asc 또는 view_desc)
    └─ PlaceResponse 변환
        ↓
[RecommendationService]
    ├─ 타입 필터링
    ├─ 시간 필터링
    ├─ PlaceResponse → StoreResponse 변환
    └─ 최대 10개 제한
        ↓
[프론트엔드 응답]
```

## 단계별 상세 설명

### 1. 요청 처리 (RecommendationController → RecommendationService)

**엔드포인트:** `POST /api/recommendations`

**요청 파라미터:**
- `latitude`: 위도
- `longitude`: 경도
- `timeOption`: 시간 옵션 (분 단위, 예: "15", "30", "45", "60", "90")
- `type`: 장소 타입 ("all", "restaurant", "cafe", "fastfood", "bar")

**처리 과정:**
1. `timeOption`을 반경(미터)으로 변환
   - 도보 속도: 5km/h
   - 계산식: `radiusMeters = (timeOption / 60) * 5000`
   - 범위: 최소 100m, 최대 5000m
2. `PlaceSearchRequest` 생성
3. `PlaceService` 호출

### 2. 장소 검색 (PlaceService)

#### 2.1 캐시 확인

**Nearby Search 캐시:**
- 키 형식: `nearby:{latitude}:{longitude}:{radius}`
- TTL: 10분
- 목적: 동일 위치/반경 검색 시 API 호출 비용 절감

**Place Details 캐시:**
- 키 형식: `place_id`
- TTL: 1시간
- 목적: 동일 장소 상세 정보 반복 조회 비용 절감

#### 2.2 Google Places Nearby Search API 호출

**캐시 미스 시:**
- API: `https://maps.googleapis.com/maps/api/place/nearbysearch/json`
- 파라미터:
  - `location`: 위도,경도
  - `radius`: 반경 (미터)
  - `type`: "restaurant" 또는 "cafe" (각각 호출하여 결과 합침)
- 반환: `place_id` 리스트
- 결과를 캐시에 저장

#### 2.3 Place Details 가져오기

각 `place_id`에 대해:
1. 캐시 확인
2. 캐시 미스 시 Google Places Place Details API 호출
   - API: `https://maps.googleapis.com/maps/api/place/details/json`
   - 필드: name, place_id, geometry, opening_hours, price_level, rating, user_ratings_total, photos, editorial_summary, reviews, formatted_address
3. 결과를 캐시에 저장

**가져오는 정보:**
- 기본 정보: 이름, 위치, 주소
- 영업 정보: 현재 영업 중 여부
- 가격 정보: 가격 수준 (0-4)
- 평점 정보: 평점, 리뷰 수
- 미디어: 사진 참조 ID
- 설명: 한 줄 요약 (editorial_summary)
- 리뷰: 상위 5개 (평점 높은 순) + 최신 5개

### 3. 도보 시간 계산

**알고리즘:** Haversine 공식

```java
distance = 2 * R * atan2(√a, √(1-a))
where:
  a = sin²(Δlat/2) + cos(lat1) * cos(lat2) * sin²(Δlon/2)
  R = 지구 반지름 (6371km)
```

**도보 시간 계산:**
- 도보 속도: 5km/h
- 계산식: `walkTimeMinutes = (distance / 5.0) * 60`

### 4. 조회수 조회

**데이터베이스:** H2 (인메모리)
**Entity:** `PlaceView`
- `placeId`: Google Places place_id
- `viewCount`: 조회수
- `createdAt`, `updatedAt`: 타임스탬프

**조회:**
- `PlaceViewRepository.findByPlaceId(placeId)`로 조회수 가져오기
- 없으면 0 반환

### 5. Hook 메시지 생성 (HookMessageService)

**목적:** 마케팅 스타일의 매력적인 메시지로 사용자의 관심 유도

**알고리즘:**

1. **주변 장소 평균 가격 계산**
   ```java
   averagePriceLevel = Σ(priceLevel + 1) / count
   ```
   - Google API 가격 수준: 0-4
   - 변환: 1-5로 변환하여 계산

2. **가격 차이 계산**
   ```java
   priceDifference = averagePriceLevel - (targetPriceLevel + 1)
   ```

3. **메시지 생성 규칙**

   **저렴한 경우** (priceDifference > 0.5):
   - 30% 이상 저렴: "여기는 {아이템}가 주변보다 {퍼센트}% 저렴해요! 가성비 최고예요."
   - 15-30% 저렴: "여기는 {아이템}가 주변보다 {퍼센트}% 저렴해요!"
   - 15% 미만: "여기는 {아이템}가 주변보다 조금 저렴해요."

   **비싼 경우** (priceDifference < -0.5):
   - 30% 이상 비쌈: "여기는 {아이템}가 주변보다 {퍼센트}% 비싸지만, 퀄리티가 확실해요."
   - 30% 미만: "여기는 {아이템}가 주변보다 조금 비싸지만, 가치가 있어요."

   **비슷한 경우** (-0.5 ≤ priceDifference ≤ 0.5):
   - 평점 4.5 이상: "여기는 {아이템}가 주변 평균 가격이지만, 평점이 높아요!"
   - 리뷰 100개 이상: "여기는 {아이템}가 주변 평균 가격이고, 리뷰가 많아요!"
   - 기본: "여기는 {아이템}가 주변 평균 가격대예요."

4. **아이템 이름 추론**
   - 장소 이름에서 키워드 추출
   - 예: "coffee", "cafe" → "아메리카노"
   - 예: "burger" → "버거"

### 6. 정렬

**옵션:**
- `price_asc`: 가격 오름차순 ("$" < "$$" < "$$$")
- `view_desc`: 조회수 내림차순 (인기 순)
- 기본: 정렬 없음 (검색 순서)

### 7. 필터링 및 변환 (RecommendationService)

#### 7.1 타입 필터링

현재는 이름 기반 추론:
- "cafe", "coffee", "카페" → cafe
- "bar", "바" → bar
- "burger", "fast", "패스트" → fastfood
- 기본: restaurant

**개선 필요:** PlaceDetails에 타입 정보 추가 필요

#### 7.2 시간 필터링

- 도보 시간이 `timeOption` 이하인 장소만 필터링
- `walkTimeMinutes <= timeOption`

#### 7.3 데이터 변환

**PlaceResponse → StoreResponse:**
- `priceLevel`: "$", "$$", "$$$" → 1, 2, 3
- `type`: 이름 기반 추론
- `estimatedDuration`: 타입 기반 추정
  - fastfood: 최대 20분
  - cafe: 최대 30분
  - restaurant: 최대 60분
  - bar: 최대 90분
- `cesReason`: Hook 메시지 또는 oneLineSummary

#### 7.4 결과 제한

- 최대 10개 반환

## 캐싱 전략

### Nearby Search 캐시
- **키:** `nearby:{latitude}:{longitude}:{radius}`
- **TTL:** 10분
- **이유:** 사용자가 같은 위치에서 반복 검색할 가능성 높음

### Place Details 캐시
- **키:** `place_id`
- **TTL:** 1시간
- **이유:** 장소 정보는 자주 변경되지 않음

### 구현
- MVP: 인메모리 캐시 (ConcurrentHashMap)
- 프로덕션: Redis로 교체 가능 (추상화된 구조)

## 성능 최적화

1. **캐싱:** API 호출 비용 및 응답 시간 감소
2. **병렬 처리 가능:** Place Details는 각각 독립적이므로 병렬 호출 가능 (추후 개선)
3. **필드 선택:** Place Details API에서 필요한 필드만 요청하여 비용 절감

## 데이터 흐름 예시

### 입력
```json
{
  "latitude": 36.1215699,
  "longitude": -115.1651093,
  "timeOption": "30",
  "type": "all"
}
```

### 처리 과정
1. 반경 계산: 30분 → 약 2500m
2. Nearby Search: restaurant, cafe 각각 호출 → 20개 place_id
3. Place Details: 20개 장소 상세 정보 가져오기
4. 도보 시간 계산: 각 장소까지의 도보 시간
5. Hook 메시지 생성: 각 장소에 대한 Hook 메시지
6. 필터링: 도보 시간 30분 이하만 선택 → 15개
7. 정렬: 가격 오름차순
8. 제한: 상위 10개

### 출력
```json
{
  "stores": [
    {
      "id": "ChIJN1t_tDeuEmsRUsoyG83frY4",
      "name": "Gordon Ramsay Burger",
      "type": "restaurant",
      "walkingTime": 12,
      "estimatedDuration": 30,
      "priceLevel": 2,
      "cesReason": "여기는 버거가 주변보다 15% 저렴해요!",
      "latitude": 36.1247,
      "longitude": -115.1628,
      "address": "1234 Las Vegas Blvd"
    }
    // ... 최대 10개
  ]
}
```

## 향후 개선 사항

1. **타입 필터링 정확도 향상**
   - PlaceDetails에 타입 정보 추가
   - Google Places API types 필드 활용

2. **병렬 처리**
   - Place Details API 호출을 병렬로 처리하여 응답 시간 단축

3. **인기 장소 우선 추천**
   - 조회수, 평점, 리뷰 수를 종합한 점수 계산
   - 가중 평균 기반 정렬

4. **개인화 추천**
   - 사용자 선호도 기반 필터링
   - 과거 선택 이력 활용

5. **실시간 혼잡도**
   - Popular Times API 통합
   - 현재 혼잡도 정보 제공

6. **에러 처리 강화**
   - API 실패 시 재시도 로직
   - Fallback 데이터 제공

## 관련 파일

- `RecommendationController.java`: API 엔드포인트
- `RecommendationService.java`: 기존 API 호환 레이어
- `PlaceService.java`: 메인 비즈니스 로직
- `GooglePlacesClient.java`: Google Places API 클라이언트
- `HookMessageService.java`: Hook 메시지 생성
- `CacheService.java`: 캐싱 서비스
- `PlaceViewRepository.java`: 조회수 관리

