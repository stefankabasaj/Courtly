package com.stefankabasaj.courtly.repository;

import com.stefankabasaj.courtly.entity.Court;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CourtRepository extends JpaRepository<Court, UUID> {

    List<Court> findAllByActiveTrue();

    boolean existsByName(String name);

    // for updates: lets a court keep its own name but not take another's
    boolean existsByNameAndIdNot(String name, UUID id);
}
