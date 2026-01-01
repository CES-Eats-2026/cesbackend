package com.ceseats.repository;

import com.ceseats.entity.PlaceView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PlaceViewRepository extends JpaRepository<PlaceView, Long> {
    Optional<PlaceView> findByPlaceId(String placeId);
}

