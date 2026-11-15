package org.osyntra.osyntrainventorymanagement.identity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.*;
import java.util.*;

/**
 * RFC 6238 HMAC-SHA1, 30-second steps; verification consumes the matched step in the database.
 */
public final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    public static String secret() {
        byte[] value = new byte[20];
        new SecureRandom().nextBytes(value);
        return base32(value);
    }

    public static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int bits = 0, buffer = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 255);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(ALPHABET.charAt((buffer >> bits) & 31));
            }
        }
        if (bits > 0) out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    private static byte[] decode(String text) {
        var out = new java.io.ByteArrayOutputStream();
        int bits = 0, buffer = 0;
        for (char c : text.toUpperCase(Locale.ROOT).toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value < 0) throw new IllegalArgumentException("Invalid Base32 secret");
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 255);
            }
        }
        return out.toByteArray();
    }

    public static String code(String secret, long step, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 15;
            int binary = ((hash[offset] & 127) << 24) | ((hash[offset + 1] & 255) << 16) | ((hash[offset + 2] & 255) << 8) | (hash[offset + 3] & 255);
            int modulus = digits == 8 ? 100000000 : 1000000;
            return String.format(Locale.ROOT, "%0" + digits + "d", binary % modulus);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static long matchedStep(String secret, String supplied, long now, long previous) {
        if (supplied == null || !supplied.matches("[0-9]{6}")) return -1;
        long step = now / 30000;
        for (long candidate = step - 1; candidate <= step + 1; candidate++)
            if (candidate > previous && MessageDigest.isEqual(code(secret, candidate, 6).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    supplied.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) return candidate;
        return -1;
    }
}
