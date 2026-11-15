package org.osyntra.osyntrainventorymanagement.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/**
 * Encrypts MFA seeds and deliverable tokens; bearer/session lookup values are hashed separately.
 */
@Component
public class SecretProtection {
    private final byte[] key;

    public SecretProtection(MvpStore db, @Value("${ORVEXA_SECURITY_KEY:}") String configured,
                            @Value("${orvexa.security.key-file:.local/orvexa-security.key}") String keyFile) throws Exception {
        var settings = db.rows("SELECT key_fingerprint FROM identity_settings WHERE id=1");
        if (configured.isBlank() && !settings.isEmpty() && !Files.exists(Path.of(keyFile), LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("Identity encryption key is missing; restore the original key before starting");
        key = configured.isBlank() ? localKey(Path.of(keyFile)) : Base64.getDecoder().decode(configured);
        if (key.length != 32)
            throw new IllegalStateException("ORVEXA_SECURITY_KEY must encode exactly 32 random bytes");
        String fingerprint = IdentitySupport.hash(Base64.getEncoder().encodeToString(key));
        db.update("INSERT INTO identity_settings(id,key_fingerprint) VALUES(1,?) ON CONFLICT(id) DO NOTHING", fingerprint);
        if (!fingerprint.equals(db.one("SELECT key_fingerprint FROM identity_settings WHERE id=1").get("key_fingerprint")))
            throw new IllegalStateException("Identity encryption key does not match this database; restore the original key");
    }

    private byte[] localKey(Path file) throws Exception {
        file = file.toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            try {
                Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException ignored) {
            }
            var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (posix != null) posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            else {
                var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
                if (acl != null) acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                        .setPrincipal(Files.getOwner(file)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            }
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("Identity key must be a regular private file");
        return Files.readAllBytes(file);
    }

    public String encrypt(String context, String plaintext) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] output = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, output, 0, iv.length);
            System.arraycopy(encrypted, 0, output, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(output);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Secret encryption failed", e);
        }
    }

    public String decrypt(String context, String ciphertext) {
        try {
            byte[] input = Base64.getDecoder().decode(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(input, 0, 12)));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Arrays.copyOfRange(input, 12, input.length)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Secret decryption failed");
        }
    }

    public String fingerprint(String scope) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(scope.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
