package com.sushimei.sushimei.backend.catalog;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuCatalogRepository extends JpaRepository<MenuItem, Long> {

    @EntityGraph(attributePaths = "tags")
    List<MenuItem> findByActiveTrueOrderByCategoryAscDisplayOrderAscNameAscIdAsc();

    @EntityGraph(attributePaths = "tags")
    List<MenuItem> findAllByOrderByCategoryAscDisplayOrderAscNameAscIdAsc();

    @EntityGraph(attributePaths = "tags")
    List<MenuItem> findByActiveTrueAndStandaloneOrderableTrueOrderByCategoryAscDisplayOrderAscNameAscIdAsc();

    @Override
    @EntityGraph(attributePaths = "tags")
    Optional<MenuItem> findById(Long id);
}
