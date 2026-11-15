package org.osyntra.osyntrainventorymanagement.identity;

import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;

@Configuration
public class IdentitySupport {
    @Bean
    public Clock identityClock() {
        return Clock.systemUTC();
    }

    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ResponseStatusException badToken() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token is invalid, expired, revoked or already used");
    }

    public static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication could not be completed");
    }

    public static void password(String password) {
        if (password == null || password.length() < 12 || password.length() > 72 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must contain 12–72 characters and at most 72 UTF-8 bytes");
    }
}
