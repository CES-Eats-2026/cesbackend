package com.ceseats.repository;

import com.ceseats.model.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StoreRepository extends JpaRepository<Store, Long> {
    List<Store> findByType(String type);
    Optional<Store> findByPlaceId(String placeId);
}

