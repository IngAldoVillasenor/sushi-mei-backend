package com.sushimei.sushimei.backend.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordPolicyService {

    private static final int MINIMUM_CODE_POINTS = 15;
    private static final int MAXIMUM_CODE_POINTS = 128;

    private final PasswordEncoder passwordEncoder;
    private final Set<String> deniedPasswordFragments;

    public PasswordPolicyService(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        this.deniedPasswordFragments = loadDenylist();
    }

    public String encodeValidated(String username, String password) {
        validate(username, password);
        return passwordEncoder.encode(password);
    }

    public boolean matches(String rawPassword, String passwordHash) {
        return passwordEncoder.matches(rawPassword, passwordHash);
    }

    public void validate(String username, String password) {
        if (password == null) {
            throw rejected();
        }
        int codePoints = password.codePointCount(0, password.length());
        if (codePoints < MINIMUM_CODE_POINTS || codePoints > MAXIMUM_CODE_POINTS) {
            throw rejected();
        }

        String comparisonForm = normalizedForPolicy(password);
        String normalizedUsername = normalizedForPolicy(username);
        if (deniedPasswordFragments.stream().anyMatch(comparisonForm::contains)
                || comparisonForm.contains(normalizedUsername)
                || comparisonForm.contains("sushimei")
                || comparisonForm.contains("sushi mei")) {
            throw rejected();
        }
    }

    private static SecurityApiException rejected() {
        return new SecurityApiException(
                "AUTH_PASSWORD_REJECTED",
                HttpStatus.BAD_REQUEST,
                "La contraseña no cumple la política de seguridad.");
    }

    private static String normalizedForPolicy(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private static Set<String> loadDenylist() {
        ClassPathResource resource = new ClassPathResource("security/password-denylist.txt");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream()))) {
            Set<String> entries = new HashSet<>();
            reader.lines()
                    .map(String::trim)
                    .filter(entry -> !entry.isEmpty() && !entry.startsWith("#"))
                    .map(PasswordPolicyService::normalizedForPolicy)
                    .forEach(entries::add);
            return Set.copyOf(entries);
        } catch (IOException exception) {
            throw new IllegalStateException("Password policy resource unavailable", exception);
        }
    }
}