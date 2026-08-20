package com.sushimei.sushimei.backend.repository;

import com.sushimei.sushimei.backend.entity.VendisOrderSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VendisOrderSnapshotRepository extends JpaRepository<VendisOrderSnapshot, Long> {
}
