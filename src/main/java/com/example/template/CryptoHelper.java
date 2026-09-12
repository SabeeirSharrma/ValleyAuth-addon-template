package com.example.template;

import com.valleyrealm.valleycert.CertificateData;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Demonstrates encrypting data with the CA-issued certificate's public key.
 *
 * Flow:
 * 1. Receive certificate from CA via ValleyCert
 * 2. Extract embedded public key (ECDSA P-256)
 * 3. Generate a random AES-256 session key
 * 4. Encrypt the session key with the CA's public key (RSA/OAEP or ECIES)
 * 5. Encrypt payload with AES-GCM using the session key
 * 6. Store encrypted session key + encrypted payload together
 *
 * NOTE: This is a simplified template. Production use should use proper
 * key wrapping (RSA-OAEP or ECDH + AES) and store keys securely.
 */
public class CryptoHelper {

    private final CertificateData certificate;

    public CryptoHelper(CertificateData certificate) {
        this.certificate = certificate;
    }

    /**
     * Encrypt a string payload using AES-GCM with a random session key.
     * The session key is encrypted with the certificate's embedded public key.
     *
     * @param plaintext The data to encrypt
     * @return EncryptedContainer with encrypted session key and payload
     */
    public EncryptedContainer encrypt(String plaintext) throws Exception {
        // 1. Generate random AES-256 session key
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey sessionKey = keyGen.generateKey();

        // 2. Encrypt payload with AES-GCM
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);

        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
        aesCipher.init(Cipher.ENCRYPT_MODE, sessionKey, gcmSpec);
        byte[] encryptedPayload = aesCipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        // 3. Encrypt session key with certificate's public key
        String publicKeyStr = certificate.getPublicKey();
        byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyStr);
        KeyFactory keyFactory = KeyFactory.getInstance("EC");
        PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));

        Cipher keyCipher = Cipher.getInstance("ECIES");
        keyCipher.init(Cipher.WRAP_MODE, publicKey);
        byte[] encryptedSessionKey = keyCipher.doFinal(sessionKey.getEncoded());

        // 4. Package encrypted session key + IV + encrypted payload
        return new EncryptedContainer(
            Base64.getEncoder().encodeToString(encryptedSessionKey),
            Base64.getEncoder().encodeToString(iv),
            Base64.getEncoder().encodeToString(encryptedPayload)
        );
    }

    /**
     * Decrypt an EncryptedContainer using the CA's private key.
     *
     * NOTE: In production, only the CA (cert.strawberry.dpdns.org) has the private key.
     * This template demonstrates the client-side encrypt path. Decryption happens
     * server-side or via a dedicated decryption service.
     */
    public String decrypt(EncryptedContainer container) throws Exception {
        // This would require the CA's private key, which is NOT available client-side.
        // In real usage, decryption happens on the server that holds the CA key.
        throw new UnsupportedOperationException(
            "Client-side decryption requires CA private key. " +
            "Use the ValleyCert API /api/certificate/decrypt endpoint instead."
        );
    }

    /**
     * Save encrypted data to a file.
     */
    public void saveEncrypted(File file, EncryptedContainer container) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(container.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Load encrypted data from a file.
     */
    public EncryptedContainer loadEncrypted(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = fis.readAllBytes();
            return EncryptedContainer.fromString(new String(data, StandardCharsets.UTF_8));
        }
    }

    /**
     * Container for encrypted data: encrypted session key + IV + encrypted payload.
     */
    public record EncryptedContainer(
        String encryptedSessionKey,
        String iv,
        String encryptedPayload
    ) {
        public String toString() {
            return encryptedSessionKey + ":" + iv + ":" + encryptedPayload;
        }

        public static EncryptedContainer fromString(String data) {
            String[] parts = data.split(":");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid encrypted container format");
            }
            return new EncryptedContainer(parts[0], parts[1], parts[2]);
        }
    }
}
