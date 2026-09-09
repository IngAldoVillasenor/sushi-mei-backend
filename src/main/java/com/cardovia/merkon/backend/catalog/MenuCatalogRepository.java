package com.cardovia.merkon.backend.catalog;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MenuCatalogRepository extends JpaRepository<MenuItem, Long> {

    @EntityGraph(attributePaths = "tags")
    List<MenuItem> findByBusinessIdAndActiveTrueOrderByCategoryAscDisplayOrderAscNameAscIdAsc(Long businessId);

    @EntityGraph(attributePaths = "tags")
    List<MenuItem> findByBusinessIdOrderByCategoryAscDisplayOrderAscNameAscIdAsc(Long businessId);

    @EntityGraph(attributePaths = "tags")
    List<MenuItem> findByBusinessIdAndActiveTrueAndStandaloneOrderableTrueOrderByCategoryAscDisplayOrderAscNameAscIdAsc(Long businessId);

    Optional<MenuItem> findByIdAndBusinessId(Long id, Long businessId);

    List<MenuItem> findByBusinessIdAndNameIgnoreCaseAndActiveTrueAndAvailableTrueAndStandaloneOrderableTrueOrderByIdAsc(
            Long businessId, String name);

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

    /** A required configuration is one whose default/empty selection is invalid. */
    @Query("select distinct g.parentMenuItem.id from MenuSelectionGroup g "
            + "where g.active = true and g.minSelections > 0 and g.parentMenuItem.business.id = :businessId and g.parentMenuItem.id in :itemIds")
    List<Long> findIdsWithRequiredSelectionGroups(@org.springframework.data.repository.query.Param("businessId") Long businessId,
                                                   @org.springframework.data.repository.query.Param("itemIds") java.util.Collection<Long> itemIds);

    /** Supports configuration without implying a cashier must make a selection. */
    @Query("select distinct g.parentMenuItem.id from MenuSelectionGroup g "
            + "where g.active = true and g.parentMenuItem.business.id = :businessId and g.parentMenuItem.id in :itemIds")
    List<Long> findIdsWithActiveSelectionGroups(@org.springframework.data.repository.query.Param("businessId") Long businessId,
                                                 @org.springframework.data.repository.query.Param("itemIds") java.util.Collection<Long> itemIds);
}
