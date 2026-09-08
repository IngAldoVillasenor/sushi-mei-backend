package com.cardovia.merkon.backend.security;
import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface RefreshTokenHistoryRepository extends JpaRepository<RefreshTokenHistory,Long> { boolean existsByTokenHash(String tokenHash); }