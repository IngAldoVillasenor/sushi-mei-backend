package com.sushimei.sushimei.backend.security;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.username = :username")
    Optional<AppUser> findByUsernameForUpdate(@Param("username") String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select u from AppUser u
            where u.active = true
              and u.role = com.sushimei.sushimei.backend.security.ApplicationRole.OWNER
            order by u.id asc
            """)
    List<AppUser> findActiveOwnersForUpdate();
}