package com.sushimei.sushimei.backend.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class SecurityTestKeyConfiguration {

    @Bean
    KeyPair securityRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}