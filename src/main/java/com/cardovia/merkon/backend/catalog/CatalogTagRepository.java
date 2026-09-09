package com.cardovia.merkon.backend.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CatalogTagRepository extends JpaRepository<CatalogTag, Long> {

    Optional<CatalogTag> findByIdAndBusinessId(Long id, Long businessId);

    Optional<CatalogTag> findByBusinessIdAndCode(Long businessId, String code);

    List<CatalogTag> findByBusinessIdAndActiveTrueOrderByDisplayOrderAscCodeAscIdAsc(Long businessId);

    List<CatalogTag> findByBusinessIdOrderByDisplayOrderAscCodeAscIdAsc(Long businessId);

    Optional<CatalogTag> findByCode(String code);

    List<CatalogTag> findByActiveTrueOrderByDisplayOrderAscCodeAscIdAsc();

    List<CatalogTag> findAllByOrderByDisplayOrderAscCodeAscIdAsc();
}
