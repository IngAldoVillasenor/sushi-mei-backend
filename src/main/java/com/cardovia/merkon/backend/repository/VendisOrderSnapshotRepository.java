package com.cardovia.merkon.backend.repository;

import com.cardovia.merkon.backend.entity.VendisOrderSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VendisOrderSnapshotRepository extends JpaRepository<VendisOrderSnapshot, Long> {
}
