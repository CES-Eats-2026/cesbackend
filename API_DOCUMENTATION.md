# CESEats Backend API 문서

## 개요

CESEats 백엔드는 Google Places API를 활용하여 주변 레스토랑과 카페를 검색하고 추천하는 REST API를 제공합니다.

## API 엔드포인트

### 1. 장소 검색

**엔드포인트:** `POST /api/places/search`

**요청 본문:**
```json
{
  "latitude": 36.1147,
  "longitude": -115.1728,
  "radius": 100,
  "sortBy": "price_asc"
}
```

**쿼리 파라미터:**
- `userLatitude` (선택): 사용자 현재 위치 위도 (도보 시간 계산용)
- `userLongitude` (선택): 사용자 현재 위치 경도 (도보 시간 계산용)

**응답 예시:**
```json
{
  "places": [
    {
      "id": "ChIJN1t_tDeuEmsRUsoyG83frY4",
      "name": "Gordon Ramsay Burger",
      "walkTimeMinutes": 12,
      "priceLevel": "$$",
      "averagePriceEstimate": 30.0,
      "openNow": true,
      "busyLevel": "MEDIUM",
      "rating": 4.5,
      "reviewCount": 1234,
      "photos": [
        "CmRaAAAA...",
        "CmRaBBBB..."
      ],
      "reviews": [
        {
          "authorName": "John Doe",
          "rating": 5,
          "text": "Great burger!",
          "time": 1609459200,
          "relativeTimeDescription": "2 months ago"
        }
      ],
      "oneLineSummary": "Famous chef Gordon Ramsay's burger restaurant",
      "googleMapUrl": "https://www.google.com/maps/place/?q=place_id:ChIJN1t_tDeuEmsRUsoyG83frY4",
      "hookMessage": "여기는 버거가 주변보다 15% 저렴해요!",
      "viewCount": 42,
      "latitude": 36.1247,
      "longitude": -115.1628,
      "address": "1234 Las Vegas Blvd, Las Vegas, NV 89101",
      "type": "restaurant"
    }
  ],
  "totalCount": 1
}
```

### 2. 장소 조회수 증가

**엔드포인트:** `POST /api/places/{placeId}/view`

**경로 파라미터:**
- `placeId`: Google Places place_id

**예시 요청:**
```
POST /api/places/ChIJN1t_tDeuEmsRUsoyG83frY4/view
```

**응답:** 200 OK (본문 없음)

## 데이터 모델

### PlaceResponse

| 필드 | 타입 | 설명 |
|------|------|------|
| id | String | Google Places place_id |
| name | String | 장소 이름 |
| walkTimeMinutes | Integer | 도보 시간 (분) |
| priceLevel | String | 가격 수준 ("$", "$$", "$$$") |
| averagePriceEstimate | Double | 평균 가격 추정 (USD) |
| openNow | Boolean | 현재 영업 중 여부 |
| busyLevel | String | 혼잡도 ("LOW", "MEDIUM", "HIGH", "UNKNOWN") |
| rating | Double | 평점 (0-5) |
| reviewCount | Long | 리뷰 수 |
| photos | List<String> | 사진 참조 ID 리스트 |
| reviews | List<ReviewDto> | 리뷰 리스트 (상위 5개 + 최신 5개) |
| oneLineSummary | String | 한 줄 요약 |
| googleMapUrl | String | Google Maps 방향 URL |
| hookMessage | String | 마케팅 Hook 메시지 |
| viewCount | Long | 조회수 |
| latitude | Double | 위도 |
| longitude | Double | 경도 |
| address | String | 주소 |

### ReviewDto

| 필드 | 타입 | 설명 |
|------|------|------|
| authorName | String | 작성자 이름 |
| rating | Integer | 평점 (1-5) |
| text | String | 리뷰 내용 |
| time | Long | Unix timestamp |
| relativeTimeDescription | String | 상대 시간 설명 (예: "2 months ago") |

## 정렬 옵션

- `price_asc`: 가격 오름차순 (저렴한 순)
- `view_desc`: 조회수 내림차순 (인기 순)

## 캐싱 전략

- **Nearby Search 결과**: 10분 TTL
- **Place Details**: 1시간 TTL

## Hook 메시지 생성 로직

Hook 메시지는 주변 장소들의 평균 가격과 비교하여 생성됩니다:

1. 주변 장소들의 평균 가격 수준 계산
2. 타겟 장소 가격과 비교
3. 가격 차이에 따라 메시지 생성:
   - 저렴한 경우: "여기는 {아이템}가 주변보다 {퍼센트}% 저렴해요!"
   - 비싼 경우: "여기는 {아이템}가 주변보다 {퍼센트}% 비싸지만, 퀄리티가 확실해요."
   - 비슷한 경우: "여기는 {아이템}가 주변 평균 가격대예요."

## 에러 처리

모든 API는 표준 HTTP 상태 코드를 반환합니다:
- `200 OK`: 성공
- `400 Bad Request`: 잘못된 요청
- `500 Internal Server Error`: 서버 오류

## 환경 변수

다음 환경 변수를 설정해야 합니다:

```bash
export GOOGLE_PLACES_API_KEY=your_api_key_here
```

API 키가 없어도 실행 가능하지만, 실제 Google Places 데이터를 사용할 수 없습니다.

