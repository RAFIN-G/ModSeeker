
import java.io.*;
import java.security.*;
import java.security.interfaces.*;
import java.util.Base64;
import java.math.BigInteger;

/**
 * KeyGen - Generates RSA-2048 keys and HMAC secret for Hidder/ModSeeker
 * Outputs formatted code blocks for direct copy-pasting.
 *
 * Generated files:
 *   SERVER_KEY_JAVA.txt      -> SecurityManager.java (Server Private Key)
 *   SERVER_VERIFY_KEY.txt    -> SecurityManager.java (Client Public Key)
 *   CLIENT_KEYS_CPP.txt      -> hidder_vault.cpp (RSA Key Components)
 *   HMAC_SECRET_JAVA.txt     -> HandshakeManager.java + SecurityManager.java
 *   HMAC_SECRET_CPP.txt      -> hidder_vault.cpp (XOR-encoded HMAC key)
 */
public class KeyGen {

    // XOR byte used to encode the HMAC key in the C++ native library.
    // This prevents the key from being extracted via 'strings' on the compiled DLL.
    private static final byte XOR_KEY = 0x5A;

    public static void main(String[] args) {
        try {
            System.out.println("====================================================");
            System.out.println("  MODSEEKER KEY FORGE — Full Key Generation Suite");
            System.out.println("====================================================");
            System.out.println();

            // ============================================================
            // PHASE 1: RSA Key Pairs
            // ============================================================

            System.out.println("[1/3] Generating RSA-2048 Server Key Pair...");
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair pair = kpg.generateKeyPair();

            RSAPrivateCrtKey privKey = (RSAPrivateCrtKey) pair.getPrivate();
            RSAPublicKey pubKey = (RSAPublicKey) pair.getPublic();

            System.out.println("      Server Key Pair generated.");

            System.out.println("[2/3] Generating RSA-2048 Client Identity Key Pair...");
            KeyPair clientPair = kpg.generateKeyPair();
            RSAPrivateCrtKey clientPriv = (RSAPrivateCrtKey) clientPair.getPrivate();
            RSAPublicKey clientPub = (RSAPublicKey) clientPair.getPublic();
            System.out.println("      Client Identity Key Pair generated.");

            // --- SERVER_KEY_JAVA.txt ---
            String serverPrivKeyB64 = Base64.getEncoder().encodeToString(privKey.getEncoded());
            StringBuilder serverKeyFile = new StringBuilder();
            serverKeyFile.append("====================================================\n");
            serverKeyFile.append("  SERVER PRIVATE KEY — For SecurityManager.java\n");
            serverKeyFile.append("====================================================\n\n");
            serverKeyFile.append("Open: ModSeeker/src/main/java/com/example/modseeker/SecurityManager.java\n");
            serverKeyFile.append("Find the line:    private static final String SERVER_PRIVATE_KEY = \"...\";\n");
            serverKeyFile.append("Replace the value between the quotes with the key below:\n\n");
            serverKeyFile.append("--- COPY BELOW THIS LINE ---\n\n");
            serverKeyFile.append("    private static final String SERVER_PRIVATE_KEY = \"" + serverPrivKeyB64 + "\";\n\n");
            serverKeyFile.append("--- END ---\n");
            writeFile("SERVER_KEY_JAVA.txt", serverKeyFile.toString());
            System.out.println("      Created SERVER_KEY_JAVA.txt");

            // --- CLIENT_KEYS_CPP.txt ---
            StringBuilder cpp = new StringBuilder();
            cpp.append("====================================================\n");
            cpp.append("  CLIENT RSA KEYS — For hidder_vault.cpp\n");
            cpp.append("====================================================\n\n");
            cpp.append("Open: Hidder/native/hidder_vault.cpp\n");
            cpp.append("Find the section labeled 'RSA KEY COMPONENTS' near the top of the file.\n");
            cpp.append("Replace ALL the const std::string lines with the ones below.\n\n");
            cpp.append("--- COPY BELOW THIS LINE ---\n\n");

            cpp.append("// --- CLIENT SHARED IDENTITY KEY (For Signing) ---\n");
            cpp.append("const std::string B64_MODULUS = \"" + toB64(clientPriv.getModulus()) + "\";\n");
            cpp.append("const std::string B64_PUB_EXP = \"" + toB64(clientPriv.getPublicExponent()) + "\";\n");
            cpp.append("const std::string B64_PRIV_EXP = \"" + toB64(clientPriv.getPrivateExponent()) + "\";\n");
            cpp.append("const std::string B64_PRIME1 = \"" + toB64(clientPriv.getPrimeP()) + "\";\n");
            cpp.append("const std::string B64_PRIME2 = \"" + toB64(clientPriv.getPrimeQ()) + "\";\n");
            cpp.append("const std::string B64_EXP1 = \"" + toB64(clientPriv.getPrimeExponentP()) + "\";\n");
            cpp.append("const std::string B64_EXP2 = \"" + toB64(clientPriv.getPrimeExponentQ()) + "\";\n");
            cpp.append("const std::string B64_COEFF = \"" + toB64(clientPriv.getCrtCoefficient()) + "\";\n\n");

            cpp.append("// --- SERVER PUBLIC KEY (For Encryption) ---\n");
            cpp.append("const std::string SRV_B64_MODULUS = \"" + toB64(privKey.getModulus()) + "\";\n");
            cpp.append("const std::string SRV_B64_EXP = \"" + toB64(privKey.getPublicExponent()) + "\";\n\n");
            cpp.append("--- END ---\n");

            writeFile("CLIENT_KEYS_CPP.txt", cpp.toString());
            System.out.println("      Created CLIENT_KEYS_CPP.txt");

            // --- SERVER_VERIFY_KEY.txt ---
            String clientPubB64 = Base64.getEncoder().encodeToString(clientPub.getEncoded());
            StringBuilder verifyFile = new StringBuilder();
            verifyFile.append("====================================================\n");
            verifyFile.append("  CLIENT PUBLIC KEY — For SecurityManager.java\n");
            verifyFile.append("====================================================\n\n");
            verifyFile.append("Open: ModSeeker/src/main/java/com/example/modseeker/SecurityManager.java\n");
            verifyFile.append("Find the line:    private static final String DEFAULT_PUBLIC_KEY = \"...\";\n");
            verifyFile.append("Replace the value between the quotes with the key below:\n\n");
            verifyFile.append("--- COPY BELOW THIS LINE ---\n\n");
            verifyFile.append("    private static final String DEFAULT_PUBLIC_KEY = \"" + clientPubB64 + "\";\n\n");
            verifyFile.append("--- END ---\n");

            writeFile("SERVER_VERIFY_KEY.txt", verifyFile.toString());
            System.out.println("      Created SERVER_VERIFY_KEY.txt");

            // ============================================================
            // PHASE 2: HMAC Shared Secret
            // ============================================================

            System.out.println("[3/3] Generating HMAC Shared Secret...");

            // Generate a random 32-character alphanumeric secret
            SecureRandom random = new SecureRandom();
            String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
            StringBuilder secretBuilder = new StringBuilder();
            for (int i = 0; i < 32; i++) {
                secretBuilder.append(chars.charAt(random.nextInt(chars.length())));
            }
            String hmacSecret = secretBuilder.toString();

            System.out.println("      HMAC Secret generated (" + hmacSecret.length() + " characters).");

            // --- HMAC_SECRET_JAVA.txt ---
            StringBuilder hmacJava = new StringBuilder();
            hmacJava.append("====================================================\n");
            hmacJava.append("  HMAC SHARED SECRET — For Java Files\n");
            hmacJava.append("====================================================\n\n");
            hmacJava.append("This secret must be IDENTICAL in both files listed below.\n");
            hmacJava.append("It is used for the Layer 1 challenge-response and Layer 3 integrity verification.\n\n");
            hmacJava.append("FILE 1: ModSeeker/src/main/java/com/example/modseeker/HandshakeManager.java\n");
            hmacJava.append("  Find:    private static final String HMAC_SECRET = \"...\";\n");
            hmacJava.append("  Replace the value between the quotes.\n\n");
            hmacJava.append("FILE 2: ModSeeker/src/main/java/com/example/modseeker/SecurityManager.java\n");
            hmacJava.append("  Find:    private static final String HMAC_SECRET = \"...\";\n");
            hmacJava.append("  Replace the value between the quotes.\n\n");
            hmacJava.append("IMPORTANT: Both files MUST have the exact same secret string!\n\n");
            hmacJava.append("--- COPY BELOW THIS LINE ---\n\n");
            hmacJava.append("    private static final String HMAC_SECRET = \"" + hmacSecret + "\";\n\n");
            hmacJava.append("--- END ---\n");

            writeFile("HMAC_SECRET_JAVA.txt", hmacJava.toString());
            System.out.println("      Created HMAC_SECRET_JAVA.txt");

            // --- HMAC_SECRET_CPP.txt ---
            byte[] secretBytes = hmacSecret.getBytes("UTF-8");
            int secretLen = secretBytes.length;

            // XOR-encode each byte
            byte[] encoded = new byte[secretLen];
            for (int i = 0; i < secretLen; i++) {
                encoded[i] = (byte) (secretBytes[i] ^ XOR_KEY);
            }

            StringBuilder hmacCpp = new StringBuilder();
            hmacCpp.append("====================================================\n");
            hmacCpp.append("  HMAC XOR-ENCODED KEY — For hidder_vault.cpp\n");
            hmacCpp.append("====================================================\n\n");
            hmacCpp.append("Open: Hidder/native/hidder_vault.cpp\n");
            hmacCpp.append("Find the computeHmac function (search for 'ENCODED_HMAC_KEY').\n");
            hmacCpp.append("Replace the ENCODED_HMAC_KEY array AND the key length with the code below.\n\n");
            hmacCpp.append("The key is XOR-encoded with 0x" + String.format("%02X", XOR_KEY) + " to prevent\n");
            hmacCpp.append("extraction via 'strings' or hex editors on the compiled DLL.\n");
            hmacCpp.append("It is decoded at runtime and zeroed from memory after use.\n\n");
            hmacCpp.append("--- COPY BELOW THIS LINE ---\n\n");

            // Format the XOR-encoded bytes as a C++ array
            hmacCpp.append("        // XOR-encoded HMAC key — decoded at runtime to prevent string extraction\n");
            hmacCpp.append("        // Original key is " + secretLen + " bytes, XOR'd with 0x" + String.format("%02X", XOR_KEY) + "\n");
            hmacCpp.append("        static const unsigned char ENCODED_HMAC_KEY[] = {\n");

            for (int i = 0; i < secretLen; i++) {
                if (i % 8 == 0) {
                    hmacCpp.append("            ");
                }
                hmacCpp.append(String.format("0x%02X", encoded[i] & 0xFF));
                if (i < secretLen - 1) {
                    hmacCpp.append(", ");
                }
                if (i % 8 == 7 || i == secretLen - 1) {
                    // Add ASCII comment at end of each row
                    int rowStart = (i / 8) * 8;
                    int rowEnd = Math.min(rowStart + 8, secretLen);
                    hmacCpp.append("  // ");
                    for (int j = rowStart; j < rowEnd; j++) {
                        hmacCpp.append((char) secretBytes[j]);
                        if (j < rowEnd - 1) hmacCpp.append(" ");
                    }
                    hmacCpp.append("\n");
                }
            }

            hmacCpp.append("        };\n");
            hmacCpp.append("        unsigned char hmacKey[" + (secretLen + 1) + "];\n");
            hmacCpp.append("        for (int i = 0; i < " + secretLen + "; i++) {\n");
            hmacCpp.append("            hmacKey[i] = ENCODED_HMAC_KEY[i] ^ 0x" + String.format("%02X", XOR_KEY) + ";\n");
            hmacCpp.append("        }\n");
            hmacCpp.append("        hmacKey[" + secretLen + "] = '\\0';\n\n");
            hmacCpp.append("        // ... (keep the rest of the function as-is) ...\n\n");
            hmacCpp.append("        HMAC(EVP_sha256(), hmacKey, " + secretLen + ", \n");
            hmacCpp.append("             (const unsigned char*)nonceStr.c_str(), nonceStr.length(), \n");
            hmacCpp.append("             hmac, &hmacLen);\n\n");
            hmacCpp.append("        // Zero the decoded key from memory\n");
            hmacCpp.append("        memset(hmacKey, 0, sizeof(hmacKey));\n\n");
            hmacCpp.append("--- END ---\n");

            writeFile("HMAC_SECRET_CPP.txt", hmacCpp.toString());
            System.out.println("      Created HMAC_SECRET_CPP.txt");

            // ============================================================
            // FINAL SUMMARY
            // ============================================================

            System.out.println();
            System.out.println("====================================================");
            System.out.println("  ALL KEYS GENERATED SUCCESSFULLY");
            System.out.println("====================================================");
            System.out.println();
            System.out.println("  5 files created in the Tools/ folder:");
            System.out.println();
            System.out.println("  [RSA Keys]");
            System.out.println("  1. SERVER_KEY_JAVA.txt      -> SecurityManager.java  (SERVER_PRIVATE_KEY)");
            System.out.println("  2. SERVER_VERIFY_KEY.txt    -> SecurityManager.java  (DEFAULT_PUBLIC_KEY)");
            System.out.println("  3. CLIENT_KEYS_CPP.txt      -> hidder_vault.cpp      (RSA key constants)");
            System.out.println();
            System.out.println("  [HMAC Secret]");
            System.out.println("  4. HMAC_SECRET_JAVA.txt     -> HandshakeManager.java (HMAC_SECRET)");
            System.out.println("                              -> SecurityManager.java  (HMAC_SECRET)");
            System.out.println("  5. HMAC_SECRET_CPP.txt      -> hidder_vault.cpp      (ENCODED_HMAC_KEY)");
            System.out.println();
            System.out.println("  Open each .txt file for detailed copy-paste instructions.");
            System.out.println();
            System.out.println("  BUILD ORDER:");
            System.out.println("  1. Paste keys into source files as instructed");
            System.out.println("  2. Compile hidder_vault.dll  (native/compile_vault.bat)");
            System.out.println("  3. Build Hidder mod          (gradlew build)");
            System.out.println("  4. Build ModSeeker plugin    (gradlew jar)");
            System.out.println();

        } catch (Exception e) {
            System.out.println("[ERROR] Key generation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String toB64(BigInteger bigInt) {
        return Base64.getEncoder().encodeToString(bigInt.toByteArray());
    }

    private static void writeFile(String filename, String content) throws IOException {
        try (PrintWriter out = new PrintWriter(filename)) {
            out.println(content);
        }
    }
}
