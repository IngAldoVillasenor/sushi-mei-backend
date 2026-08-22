package com.sushimei.sushimei.backend.repository;

import com.sushimei.sushimei.backend.entity.BusinessDayClosure;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessDayClosureRepository extends JpaRepository<BusinessDayClosure, Long> {

    Optional<BusinessDayClosure> findTopByBusinessDayIdOrderByCloseNumberDesc(Long businessDayId);

    List<BusinessDayClosure> findByBusinessDayIdOrderByCloseNumberAsc(Long businessDayId);

    boolean existsByBusinessDayId(Long businessDayId);
}
