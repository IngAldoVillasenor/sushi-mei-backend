package com.cardovia.merkon.backend.security;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByEmail(String email);

    @Query("""
            select case when count(userAccount) > 0 then true else false end
            from AppUser userAccount
            where lower(userAccount.username) in :aliases
               or (userAccount.email is not null and lower(userAccount.email) in :aliases)
            """)
    boolean existsByUsernameOrEmailAliasIgnoreCase(@Param("aliases") Collection<String> aliases);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.username = :username")
    Optional<AppUser> findByUsernameForUpdate(@Param("username") String username);

}
