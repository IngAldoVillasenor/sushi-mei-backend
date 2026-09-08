package com.cardovia.merkon.backend.business;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessRepository extends JpaRepository<Business, Long> {

    List<Business> findByActiveTrueOrderByIdAsc();
}
