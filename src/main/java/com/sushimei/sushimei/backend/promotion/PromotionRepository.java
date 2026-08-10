package com.sushimei.sushimei.backend.promotion;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    @EntityGraph(attributePaths = {"isoWeekdays", "targets", "targets.targetMenuItem", "targets.targetTag"})
    List<Promotion> findByActiveTrueOrderByPriorityDescIdAsc();

    @EntityGraph(attributePaths = {"isoWeekdays", "targets", "targets.targetMenuItem", "targets.targetTag"})
    List<Promotion> findAllByOrderByPriorityDescIdAsc();

    @Override
    @EntityGraph(attributePaths = {"isoWeekdays", "targets", "targets.targetMenuItem", "targets.targetTag"})
    Optional<Promotion> findById(Long id);
}
