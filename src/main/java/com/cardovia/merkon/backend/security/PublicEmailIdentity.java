package com.cardovia.merkon.backend.security;

import java.net.IDN;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Canonical public-email identity plus the finite legacy aliases that can
 * represent the same IDN domain. This intentionally is not a general
 * username normalizer.
 */
record PublicEmailIdentity(String canonicalAscii, Set<String> aliases) {

    static PublicEmailIdentity require(String rawEmail) {
        String email = normalize(rawEmail);
        if (email.isEmpty() || email.length() > 254 || containsControlOrWhitespace(email)) {
            throw invalid();
        }
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) {
            throw invalid();
        }
        String localPart = email.substring(0, at);
        String domain = email.substring(at + 1);
        if (localPart.length() > 64 || localPart.startsWith(".") || localPart.endsWith(".")
                || localPart.contains("..") || domain.startsWith(".") || domain.endsWith(".")) {
            throw invalid();
        }
        String asciiDomain;
        try {
            asciiDomain = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (asciiDomain.isEmpty() || asciiDomain.length() > 253 || !asciiDomain.contains(".")) {
                throw invalid();
            }
            for (String label : asciiDomain.split("\\.")) {
                if (label.isEmpty() || label.length() > 63) {
                    throw invalid();
                }
            }
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }

        String normalizedLocalPart = localPart.toLowerCase(Locale.ROOT);
        String canonicalAscii = normalizedLocalPart + "@" + asciiDomain;
        String unicodeAlias = normalizedLocalPart + "@" + IDN.toUnicode(asciiDomain).toLowerCase(Locale.ROOT);
        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(canonicalAscii);
        aliases.add(unicodeAlias);
        return new PublicEmailIdentity(canonicalAscii, Set.copyOf(aliases));
    }

    static PublicEmailIdentity orNull(String rawEmail) {
        try {
            return require(rawEmail);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).strip();
    }

    private static boolean containsControlOrWhitespace(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint) || Character.isWhitespace(codePoint));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid public email identity");
    }
}
