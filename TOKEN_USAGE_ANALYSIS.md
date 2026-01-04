# RAG 추천 기능 토큰 사용량 분석

## LLM 호출 횟수
한 번의 RAG 추천 요청당 **2번의 LLM 호출**이 발생합니다.

### 1. Preference 파싱 (Step 1)
- **목적**: 사용자 자연어 입력 → 구조화된 필터
- **프롬프트 크기**: ~300-400 tokens
- **응답 크기**: ~100-200 tokens (JSON)
- **max_tokens**: 500 (설정값)
- **예상 사용량**: ~400-600 tokens

**프롬프트 예시:**
```
Parse the following user preference into structured filters...
User preference: "매운 한국 음식 저녁 식사, 너무 비싸지 않은 곳"
Output format: {...}
Rules: ...
```
→ 약 300-400 tokens

### 2. 최종 랭킹 (Step 5)
- **목적**: 필터링된 후보들 → 랭킹 + 이유
- **프롬프트 크기**: 가변적 (후보 개수에 따라)
  - 후보 10개: ~800-1200 tokens
  - 후보 20개: ~1500-2000 tokens
  - 후보 50개: ~3500-4500 tokens
- **응답 크기**: ~200-300 tokens (JSON)
- **max_tokens**: 500 (설정값)
- **예상 사용량**: 후보 개수에 따라 크게 변동

**프롬프트 구조:**
```
Rank the following restaurants...
User preference: "..."
Restaurants:
1. Restaurant Name
   Types: korean_restaurant, restaurant
   Price: $10 ~ $20
   Address: ...
   Reviews: ...
2. ...
```
→ 후보당 약 80-100 tokens

## 총 토큰 사용량 (예상) - 최대 3개 추천 기준

### 현재 설정: 최대 3개 추천
- Preference 파싱:
  - Input: ~400 tokens
  - Output: ~150 tokens
  - **소계: ~550 tokens**

- 랭킹 (3개 후보):
  - Input: ~500-600 tokens (후보 3개 정보)
  - Output: ~200 tokens (JSON 응답)
  - **소계: ~700-800 tokens**

- **총합: ~1,250-1,350 tokens**
  - Input 총합: ~900-1,000 tokens
  - Output 총합: ~350 tokens

### 이전 설정 (참고): 최대 10개 추천
- Preference 파싱: ~550 tokens
- 랭킹 (10개 후보): ~1,200 tokens
- **총합: ~1,750 tokens**

## 비용 계산 (gpt-4o-mini 기준)

### 가격 (2024년 기준)
- Input: $0.15 / 1M tokens
- Output: $0.60 / 1M tokens

### 현재 설정: 최대 3개 추천 (한 번의 질문당)
**Input 비용:**
- Preference 파싱: ~400 tokens × $0.15/1M = $0.00006
- 랭킹: ~600 tokens × $0.15/1M = $0.00009
- **Input 총: ~1,000 tokens × $0.15/1M = $0.00015**

**Output 비용:**
- Preference 파싱: ~150 tokens × $0.60/1M = $0.00009
- 랭킹: ~200 tokens × $0.60/1M = $0.00012
- **Output 총: ~350 tokens × $0.60/1M = $0.00021**

**총 비용: $0.00036 ≈ $0.0004**

**한국 원화 환산 (1 USD = 1,300 KRW 기준):**
- **$0.0004 × 1,300 = 약 0.52원**

**정리: 한 번의 질문당 약 0.5원**

### 이전 설정 (참고): 최대 10개 추천
- Input: ~1,200 tokens × $0.15/1M = $0.00018
- Output: ~550 tokens × $0.60/1M = $0.00033
- **총: ~$0.00051 (약 0.66원)**

## 최적화 방안

### 1. 후보 개수 제한
- 현재: 필터링 후 모든 후보를 LLM에 전달
- 개선: 최대 20개로 제한 (PostgreSQL/Redis 필터링 강화)

### 2. 프롬프트 최적화
- 리뷰 요약 길이 제한 (현재 3개 → 1-2개)
- 주소 정보 생략 또는 간소화

### 3. 캐싱
- 동일한 선호도 패턴에 대한 결과 캐싱
- Redis에 1시간 TTL로 저장

### 4. 배치 처리
- 여러 사용자 요청을 모아서 배치로 처리 (비용 절감)

## 실제 사용량 모니터링

코드에 토큰 사용량 로깅 추가 권장:
```java
// LLMService.callLLM()에서
JsonNode usage = root.get("usage");
if (usage != null) {
    int promptTokens = usage.get("prompt_tokens").asInt();
    int completionTokens = usage.get("completion_tokens").asInt();
    int totalTokens = usage.get("total_tokens").asInt();
    logger.info("Token usage - prompt: {}, completion: {}, total: {}", 
                promptTokens, completionTokens, totalTokens);
}
```

