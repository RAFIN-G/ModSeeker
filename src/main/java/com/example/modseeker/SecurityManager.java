/*
 * Copyright (C) 2025 ModSeeker
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.example.modseeker;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Manages security functions including signature verification and replay attack
 * protection.
 */
public class SecurityManager {

    private final ModSeekerPlugin plugin;
    private PublicKey publicKey;
    private boolean validationEnabled = false;

    // Public key for verifying signatures (corresponds to private key in Hidder
    // mod)
    private static final String DEFAULT_PUBLIC_KEY = "PLACEHOLDER";

    // Server Private Key for Decryption
    private static final String SERVER_PRIVATE_KEY = "PLACEHOLDER";

    private java.security.PrivateKey privateKey;

    public SecurityManager(ModSeekerPlugin plugin) {
        this.plugin = plugin;
        loadPublicKey();
        loadPrivateKey();
    }

    private void loadPrivateKey() {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(SERVER_PRIVATE_KEY);
            java.security.spec.PKCS8EncodedKeySpec spec = new java.security.spec.PKCS8EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            this.privateKey = kf.generatePrivate(spec);
        } catch (Exception e) {
            plugin.logInfo("❌ Security: Failed to load private key: " + e.getMessage());
        }
    }

    public String decrypt(String ciphertext) {
        try {
            String[] parts = ciphertext.split("\\|");

            if (parts.length != 3) {
                plugin.logInfo("❌ Security: Invalid ciphertext format. Expected 3 parts, got " + parts.length);
                return null;
            }

            String encKeyB64 = parts[0];
            String ivB64 = parts[1];
            String encDataB64 = parts[2];

            byte[] decodedKeyBytes = Base64.getMimeDecoder().decode(encKeyB64);

            if (decodedKeyBytes.length > 256) {
                plugin.logInfo("❌ Security: Invalid encrypted key length: " + decodedKeyBytes.length);
                return null;
            }

            javax.crypto.Cipher rsaCipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsaCipher.init(javax.crypto.Cipher.DECRYPT_MODE, privateKey);
            byte[] aesKey = rsaCipher.doFinal(decodedKeyBytes);

            javax.crypto.Cipher aesCipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(aesKey, "AES");
            javax.crypto.spec.IvParameterSpec ivSpec = new javax.crypto.spec.IvParameterSpec(
                    Base64.getMimeDecoder().decode(ivB64));

            aesCipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec);
            byte[] decodedData = aesCipher.doFinal(Base64.getMimeDecoder().decode(encDataB64));

            return new String(decodedData, StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.logInfo("❌ Security: Decryption failed: " + e.getMessage());
            plugin.logInfo("❌ Security: Error handling encrypted response: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private String listToJsonArray(String commaSeparated) {
        if (commaSeparated.isEmpty())
            return "[]";
        StringBuilder sb = new StringBuilder("[");
        String[] items = commaSeparated.split(",");
        for (int i = 0; i < items.length; i++) {
            if (i > 0)
                sb.append(",");
            sb.append("\"").append(items[i]).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private void loadPublicKey() {
        try {
            String publicKeyStr = DEFAULT_PUBLIC_KEY;

            if (publicKeyStr.contains("PLACEHOLDER")) {
                plugin.logInfo("⚠️ Security: No valid public key found. Signature validation DISABLED.");
                validationEnabled = false;
                return;
            }

            byte[] keyBytes = Base64.getDecoder().decode(publicKeyStr);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            this.publicKey = kf.generatePublic(spec);
            this.validationEnabled = true;
        } catch (Exception e) {
            plugin.logInfo("❌ Security: Failed to load public key: " + e.getMessage());
            validationEnabled = false;
        }
    }

    public boolean verifySignature(String data, String signatureBase64) {
        if (!validationEnabled) {
            return true;
        }

        if ("ENCRYPTED_CHANNEL".equals(signatureBase64)) {
            return true;
        }

        try {
            Signature sign = Signature.getInstance("SHA256withRSA");
            sign.initVerify(publicKey);
            sign.update(data.getBytes(StandardCharsets.UTF_8));

            byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
            return sign.verify(signatureBytes);

        } catch (Exception e) {
            plugin.logInfo("❌ Security: Signature verification error: " + e.getMessage());
            return false;
        }
    }

    public boolean verifyTimestamp(long timestamp) {
        if (!validationEnabled) {
            return true;
        }
        long currentTime = System.currentTimeMillis();
        long diff = Math.abs(currentTime - timestamp);
        return diff < 30000;
    }

    public boolean isValidationEnabled() {
        return validationEnabled;
    }

    public void handleEncryptedResponse(org.bukkit.entity.Player player, String jsonMessage) {
        try {
            // Extract ciphertext manually since we don't use a JSON lib
            String tag = "\"ciphertext\":\"";
            int start = jsonMessage.indexOf(tag);
            if (start == -1) {
                plugin.logInfo("❌ Security: Encrypted response missing ciphertext tag from " + player.getName());
                return;
            }
            start += tag.length();
            int end = jsonMessage.indexOf("\"", start);
            if (end == -1) {
                plugin.logInfo("❌ Security: Malformed ciphertext JSON from " + player.getName());
                return;
            }

            String ciphertext = jsonMessage.substring(start, end);

            // Now decrypt just the ciphertext
            String decryptedData = decrypt(ciphertext);

            if (decryptedData == null) {
                plugin.logInfo("❌ Security: Decryption of encrypted response failed.");
                return;
            }

            // Parse the decrypted data:
            // checkId=...|mods=...|resourcePacks=...|shaderPacks=...

            String checkId = "unknown";
            String mods = "";
            String resPacks = "";
            String shaders = "";

            String[] fields = decryptedData.split("\\|");
            for (String field : fields) {
                if (field.startsWith("checkId="))
                    checkId = field.substring("checkId=".length());
                else if (field.startsWith("mods="))
                    mods = field.substring("mods=".length());
                else if (field.startsWith("resourcePacks="))
                    resPacks = field.substring("resourcePacks=".length());
                else if (field.startsWith("shaderPacks="))
                    shaders = field.substring("shaderPacks=".length());
            }

            String modsJson = listToJsonArray(mods);
            String resPacksJson = listToJsonArray(resPacks);
            String shadersJson = listToJsonArray(shaders);

            long currentTimestamp = System.currentTimeMillis();

            StringBuilder reconstitutedJson = new StringBuilder();
            reconstitutedJson.append("{\"messageType\":\"RESPONSE_MODLIST\",");
            reconstitutedJson.append("\"modId\":\"hidder\",");
            reconstitutedJson.append("\"version\":\"1.21.10\",");
            reconstitutedJson.append("\"checkId\":\"").append(checkId).append("\",");
            reconstitutedJson.append("\"mods\":").append(modsJson).append(",");
            reconstitutedJson.append("\"resourcePacks\":").append(resPacksJson).append(",");
            reconstitutedJson.append("\"shaderPacks\":").append(shadersJson).append(",");

            reconstitutedJson.append("\"signature\":\"ENCRYPTED_CHANNEL\",");
            reconstitutedJson.append("\"timestamp\":").append(currentTimestamp);

            reconstitutedJson.append("}");

            plugin.handleModListResponse(player, reconstitutedJson.toString());

        } catch (Exception e) {
            plugin.logInfo("❌ Security: Error handling encrypted response: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int startIndex = json.indexOf(searchKey);
        if (startIndex != -1) {
            startIndex += searchKey.length();
            int endIndex = json.indexOf("\"", startIndex);
            if (endIndex != -1) {
                return json.substring(startIndex, endIndex);
            }
        }
        searchKey = "\"" + key + "\":";
        startIndex = json.indexOf(searchKey);
        if (startIndex != -1) {
            startIndex += searchKey.length();
            int braceCount = 0;
            int bracketCount = 0;
            for (int i = startIndex; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{')
                    braceCount++;
                else if (c == '}')
                    braceCount--;
                else if (c == '[')
                    bracketCount++;
                else if (c == ']')
                    bracketCount--;

                if ((c == ',' || c == '}') && braceCount == 0 && bracketCount == 0) {
                    return json.substring(startIndex, i).trim();
                }
            }
            return json.substring(startIndex).trim();
        }
        return "";
    }
}
