package com.sushimei.sushimei.backend.catalog;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MenuCatalogRepository extends JpaRepository<MenuItem, Long> {

    @EntityGraph(attributePaths = "tags")
    List<MenuItem> findByActiveTrueOrderByCategoryAscDisplayOrderAscNameAscIdAsc();

    @EntityGraph(attributePaths = "tags")
    List<MenuItem> findAllByOrderByCategoryAscDisplayOrderAscNameAscIdAsc();

    @EntityGraph(attributePaths = "tags")
    List<MenuItem> findByActiveTrueAndStandaloneOrderableTrueOrderByCategoryAscDisplayOrderAscNameAscIdAsc();

    List<MenuItem> findByNameIgnoreCaseAndActiveTrueAndAvailableTrueAndStandaloneOrderableTrueOrderByIdAsc(String name);

    @Override
    @EntityGraph(attributePaths = "tags")
    Optional<MenuItem> findById(Long id);

    @Query("select distinct g.parentMenuItem.id from MenuSelectionGroup g where g.active = true and g.parentMenuItem.id in :itemIds")
    List<Long> findIdsWithActiveSelectionGroups(java.util.Collection<Long> itemIds);
}
