# RAG-Based Restaurant Recommendation Implementation

## 1. API Flow

```
POST /api/rag/recommendations
Request: { latitude, longitude, maxDistanceKm, userPreference }
  ↓
Step 1: LLM parses userPreference → PreferenceFilters
  ↓
Step 2: PostgreSQL filters by location, distance, price
  ↓
Step 3: Redis filters by types, features
  ↓
Step 4: Build PlaceContext objects (name, types, reviews, price)
  ↓
Step 5: LLM ranks filtered candidates → Top 10 recommendations
  ↓
Response: List<StoreResponse>
```

## 2. Data Flow

```
Input: Free-text preference
  ↓
[LLM Parse] → PreferenceFilters {
  cuisineTypes: ["korean_restaurant", "japanese_restaurant"],
  placeTypes: ["restaurant"],
  minPriceLevel: 1,
  maxPriceLevel: 2,
  minRating: 4.0,
  keywords: ["spicy"],
  mealType: "dinner"
}
  ↓
[PostgreSQL Query] → Filter by:
  - Location (lat/lng within radius)
  - Distance (maxDistanceKm)
  - Price level (minPriceLevel, maxPriceLevel)
  ↓
[Redis Filter] → Filter by:
  - Types match (placeTypes, cuisineTypes)
  - Features match (keywords from reviews/types)
  ↓
[Build Context] → PlaceContext[] {
  placeId, name, types[], priceLevel, reviews[]
}
  ↓
[LLM Rank] → Ranked place IDs
  ↓
Output: StoreResponse[] (top 10)
```

## 3. LLM Prompts

### 3.1 Preference Parsing Prompt

```
Parse the following user preference into structured filters. 
Output ONLY valid JSON, no explanations.

User preference: "{userPreference}"

Output format:
{
  "cuisineTypes": ["korean", "japanese"],
  "placeTypes": ["restaurant", "cafe"],
  "minPriceLevel": 1,
  "maxPriceLevel": 3,
  "minRating": 4.0,
  "keywords": ["spicy", "vegetarian"],
  "mealType": "dinner"
}

Rules:
- cuisineTypes: Use Google Places types (korean_restaurant, japanese_restaurant, etc.)
- placeTypes: restaurant, cafe, fast_food_restaurant, bar, etc.
- priceLevel: 1=$, 2=$$, 3=$$$
- If not specified, use null or empty array
Output JSON only:
```

### 3.2 Final Ranking Prompt

```
Rank the following restaurants based on user preference. 
Output ONLY a JSON array of place IDs in ranked order, no explanations.

User preference: "{userPreference}"

Restaurants:
{contexts}

Output format: ["place_id_1", "place_id_2", ...]
Output JSON only:
```

## 4. Redis Key Schema

```
types:{place_id} → List<String>
  Example: types:ChIJ... → ["korean_restaurant", "restaurant", "food"]

reviews:{place_id} → List<Map>
  Example: reviews:ChIJ... → [
    {"authorName": "John", "rating": 5, "text": "Great food", ...},
    ...
  ]
```

## 5. PostgreSQL Query Example

```sql
-- Find stores within radius (existing method)
SELECT 
  place_id, name, latitude, longitude, 
  price_level, address, reason
FROM stores
WHERE (
  6371 * acos(
    cos(radians(:latitude)) * 
    cos(radians(latitude)) * 
    cos(radians(longitude) - radians(:longitude)) + 
    sin(radians(:latitude)) * 
    sin(radians(latitude))
  )
) <= :radiusKm
ORDER BY (
  6371 * acos(
    cos(radians(:latitude)) * 
    cos(radians(latitude)) * 
    cos(radians(longitude) - radians(:longitude)) + 
    sin(radians(:latitude)) * 
    sin(radians(latitude))
  )
);
```

## 6. Service Code Structure

```java
@Service
public class RagRecommendationService {
    // Step 1: Parse preference
    PreferenceFilters parsePreference(String userPreference)
    
    // Step 2: PostgreSQL filter
    List<Store> filterByPostgreSQL(lat, lng, distance, filters)
    
    // Step 3: Redis filter
    List<Store> filterByRedis(candidates, filters)
    
    // Step 4: Build context
    List<PlaceContext> buildPlaceContexts(stores)
    
    // Step 5: LLM ranking
    List<StoreResponse> rankWithLLM(contexts, userPreference)
}
```

## 7. Token Usage Optimization

- **Preference parsing**: ~200 tokens (single call)
- **Ranking**: ~500-1000 tokens (depends on candidate count)
- **Total**: ~700-1200 tokens per request
- **Caching**: Consider caching parsed filters for similar queries

## 8. Example Request/Response

**Request:**
```json
{
  "latitude": 36.1215699,
  "longitude": -115.1651093,
  "maxDistanceKm": 5,
  "userPreference": "I want spicy Korean food for dinner, not too expensive"
}
```

**Response:**
```json
[
  {
    "id": "ChIJ...",
    "name": "Korean BBQ House",
    "type": "restaurant",
    "types": ["korean_restaurant", "restaurant"],
    "priceLevel": 2,
    "latitude": 36.123,
    "longitude": -115.167,
    ...
  },
  ...
]
```

