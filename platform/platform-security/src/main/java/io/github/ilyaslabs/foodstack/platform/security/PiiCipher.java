package io.github.ilyaslabs.foodstack.platform.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Field encryption for personal data (TDD 6.2): AES-256-GCM for the value and a keyed HMAC blind
 * index for lookups. Both keys are derived from one master key, so one Secret covers both.
 */
public class PiiCipher {

    private static final int IV_LENGTH = 12;

    private static final int TAG_BITS = 128;

    private final SecretKeySpec encryptionKey;

    private final SecretKeySpec indexKey;

    private final SecureRandom random = new SecureRandom();

    public PiiCipher(String base64MasterKey) {
        byte[] master = Base64.getDecoder().decode(base64MasterKey);
        if (master.length < 32) {
            throw new IllegalArgumentException("foodstack.security.pii-key must be at least 32 bytes (base64)");
        }
        this.encryptionKey = new SecretKeySpec(derive(master, "foodstack-pii-encryption"), "AES");
        this.indexKey = new SecretKeySpec(derive(master, "foodstack-pii-index"), "HmacSHA256");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            this.random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, this.encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not encrypt", ex);
        }
    }

    public String decrypt(String ciphertext) {
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, this.encryptionKey, new GCMParameterSpec(TAG_BITS, all, 0, IV_LENGTH));
            return new String(cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH), StandardCharsets.UTF_8);
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not decrypt", ex);
        }
    }

    /** A deterministic, keyed index of a normalized value (trimmed, lower case). */
    public String blindIndex(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(this.indexKey, normalized.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] derive(byte[] master, String purpose) {
        return hmac(new SecretKeySpec(master, "HmacSHA256"), purpose.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(SecretKeySpec key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data);
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }
}
