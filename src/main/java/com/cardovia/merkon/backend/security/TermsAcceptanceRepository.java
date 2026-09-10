package com.cardovia.merkon.backend.security;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TermsAcceptanceRepository extends JpaRepository<TermsAcceptance, Long> {

    List<TermsAcceptance> findByUserIdOrderByAcceptedAtAsc(Long userId);
}
