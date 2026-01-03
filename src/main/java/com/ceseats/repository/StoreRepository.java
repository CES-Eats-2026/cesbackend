package com.ceseats.repository;

import com.ceseats.model.Store;
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
}

