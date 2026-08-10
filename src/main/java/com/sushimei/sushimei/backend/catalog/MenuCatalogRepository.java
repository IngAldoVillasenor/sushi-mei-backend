package com.sushimei.sushimei.backend.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuCatalogRepository extends JpaRepository<MenuItem, Long> {

    List<MenuItem> findByActiveTrueOrderByCategoryAscDisplayOrderAscNameAscIdAsc();

    List<MenuItem> findAllByOrderByCategoryAscDisplayOrderAscNameAscIdAsc();
}
