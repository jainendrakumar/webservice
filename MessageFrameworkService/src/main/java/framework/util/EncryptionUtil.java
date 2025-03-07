package framework.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * Provides utility methods for encrypting and decrypting messages using AES.
 */
public class EncryptionUtil {

    private static final String ALGORITHM = "AES";

    /**
     * Encrypts the given plain text message using the provided key.
     *
     * @param message the plain text message.
     * @param key     the encryption key (must be 16 bytes for AES-128).
     * @return the Base64-encoded encrypted message.
     * @throws Exception if encryption fails.
     */
    public static String encrypt(String message, String key) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes("UTF-8"), ALGORITHM);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encryptedBytes = cipher.doFinal(message.getBytes("UTF-8"));
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    /**
     * Decrypts the given Base64-encoded encrypted message using the provided key.
     *
     * @param encryptedMessage the encrypted message.
     * @param key              the decryption key.
     * @return the decrypted plain text message.
     * @throws Exception if decryption fails.
     */
    public static String decrypt(String encryptedMessage, String key) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes("UTF-8"), ALGORITHM);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        byte[] decodedBytes = Base64.getDecoder().decode(encryptedMessage);
        byte[] decryptedBytes = cipher.doFinal(decodedBytes);
        return new String(decryptedBytes, "UTF-8");
    }
}
