package com.sushimei.sushimei.backend.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuSelectionRuleRepository extends JpaRepository<MenuSelectionRule, Long> {

    List<MenuSelectionRule> findBySelectionGroupIdAndActiveTrueOrderByPriorityDescIdAsc(Long selectionGroupId);

    List<MenuSelectionRule> findBySelectionGroupIdOrderByPriorityDescIdAsc(Long selectionGroupId);

    Optional<MenuSelectionRule> findByIdAndSelectionGroupId(Long id, Long selectionGroupId);
}
