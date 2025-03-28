package framework.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * The {@code EncryptionUtil} class provides static utility methods for encrypting and decrypting messages
 * using AES symmetric encryption.
 * <p>
 * The encryption method uses AES (Advanced Encryption Standard) to encrypt the input message with the provided key.
 * The key must be 16 bytes long for AES-128. The output is a Base64-encoded string of the encrypted data.
 * Similarly, the decryption method reverses this process.
 * </p>
 *
 * <p>
 * <b>Usage Example:</b>
 * <pre>
 * String secretKey = "MySecretAESKey123"; // Must be 16 characters for AES-128
 * String plainText = "Hello, World!";
 * String encrypted = EncryptionUtil.encrypt(plainText, secretKey);
 * String decrypted = EncryptionUtil.decrypt(encrypted, secretKey);
 * </pre>
 * </p>
 *
 * @see javax.crypto.Cipher
 * @see javax.crypto.spec.SecretKeySpec
 *
 * @author jkr3 (Jainendra Kumar)
 */
public class EncryptionUtil {

    private static final String ALGORITHM = "AES";

    /**
     * Encrypts the given plain text message using the specified AES encryption key.
     *
     * @param message the plain text message to encrypt.
     * @param key     the encryption key (must be 16 bytes for AES-128).
     * @return the Base64-encoded encrypted message.
     * @throws Exception if encryption fails.
     */
    public static String encrypt(String message, String key) throws Exception {
        // Create a secret key specification from the provided key.
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes("UTF-8"), ALGORITHM);
        // Get an AES cipher instance.
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        // Initialize the cipher in encryption mode.
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        // Perform encryption.
        byte[] encryptedBytes = cipher.doFinal(message.getBytes("UTF-8"));
        // Return the encrypted data as a Base64-encoded string.
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    /**
     * Decrypts the given Base64-encoded encrypted message using the specified AES encryption key.
     *
     * @param encryptedMessage the encrypted message as a Base64-encoded string.
     * @param key              the decryption key (must be the same as used for encryption).
     * @return the decrypted plain text message.
     * @throws Exception if decryption fails.
     */
    public static String decrypt(String encryptedMessage, String key) throws Exception {
        // Create a secret key specification from the provided key.
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes("UTF-8"), ALGORITHM);
        // Get an AES cipher instance.
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        // Initialize the cipher in decryption mode.
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        // Decode the Base64-encoded encrypted message.
        byte[] decodedBytes = Base64.getDecoder().decode(encryptedMessage);
        // Perform decryption.
        byte[] decryptedBytes = cipher.doFinal(decodedBytes);
        // Return the decrypted data as a string.
        return new String(decryptedBytes, "UTF-8");
    }
}
