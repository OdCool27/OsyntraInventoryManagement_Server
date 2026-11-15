package org.osyntra.osyntrainventorymanagement;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.osyntra.osyntrainventorymanagement.identity.Totp;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TotpTests {
    @ParameterizedTest
    @CsvSource({"59,94287082", "1111111109,07081804", "1111111111,14050471", "1234567890,89005924", "2000000000,69279037", "20000000000,65353130"})
    void matchesRfc6238Sha1TestVectors(long seconds, String expected) {
        String secret = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        assertEquals(expected, Totp.code(secret, seconds / 30, 8));
    }
}
