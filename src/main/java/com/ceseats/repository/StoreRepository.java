package com.ceseats.repository;

import com.ceseats.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StoreRepository extends JpaRepository<Store, String> {
    Optional<Store> findByPlaceId(String placeId);

    /**
     * 원형 거리 내의 장소들을 조회 (Haversine 공식 사용)
     * @param latitude 사용자 위도
     * @param longitude 사용자 경도
     * @param radiusKm 반경 (km)
     * @return 반경 내의 장소 리스트
     */
    @Query(value = """
        SELECT * FROM stores
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
        ) ASC
        """, nativeQuery = true)
    List<Store> findStoresWithinRadius(
        @Param("latitude") double latitude,
        @Param("longitude") double longitude,
        @Param("radiusKm") double radiusKm
    );

    /**
     * 원형 거리 내의 장소들을 랜덤으로 3개만 조회 (Haversine 공식 사용)
     * 서브쿼리로 거리 계산 후 랜덤 선택
     * @param latitude 사용자 위도
     * @param longitude 사용자 경도
     * @param radiusKm 반경 (km)
     * @return 반경 내의 장소 리스트 (랜덤 3개)
     */
    @Query(value = """
        SELECT * FROM (
            SELECT *, (
                6371 * acos(
                    cos(radians(:latitude)) * 
                    cos(radians(latitude)) * 
                    cos(radians(longitude) - radians(:longitude)) + 
                    sin(radians(:latitude)) * 
                    sin(radians(latitude))
                )
            ) AS distance
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
        ) AS filtered_stores
        ORDER BY RANDOM()
        LIMIT 3
        """, nativeQuery = true)
    List<Store> findRandomStoresWithinRadius(
        @Param("latitude") double latitude,
        @Param("longitude") double longitude,
        @Param("radiusKm") double radiusKm
    );

    /**
     * 원형 거리 내의 장소들을 조회하고 place_id 리스트로 필터링 (Haversine 공식 사용)
     * @param latitude 사용자 위도
     * @param longitude 사용자 경도
     * @param radiusKm 반경 (km)
     * @param placeIds 필터링할 place_id 리스트
     * @return 반경 내의 장소 리스트 (place_id 필터링 적용)
     */
    @Query(value = """
        SELECT * FROM stores
        WHERE place_id IN :placeIds
        AND (
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
        ) ASC
        """, nativeQuery = true)
    List<Store> findStoresWithinRadiusAndPlaceIds(
        @Param("latitude") double latitude,
        @Param("longitude") double longitude,
        @Param("radiusKm") double radiusKm,
        @Param("placeIds") List<String> placeIds
    );
}

