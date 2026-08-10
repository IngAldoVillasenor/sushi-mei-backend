package com.sushimei.sushimei.backend.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CatalogTagRepository extends JpaRepository<CatalogTag, Long> {

    Optional<CatalogTag> findByCode(String code);

    List<CatalogTag> findByActiveTrueOrderByDisplayOrderAscCodeAscIdAsc();

    List<CatalogTag> findAllByOrderByDisplayOrderAscCodeAscIdAsc();
}
