package com.cardovia.merkon.backend.security;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SecurityAuditEventRepository extends JpaRepository<SecurityAuditEvent,Long> { }