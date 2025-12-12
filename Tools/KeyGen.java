
import java.io.*;
import java.security.*;
import java.security.interfaces.*;
import java.util.Base64;
import java.math.BigInteger;

/**
 * KeyGen - Generates RSA-2048 keys for Hidder/ModSeeker
 * Outputs formatted code blocks for direct copy-pasting.
 */
public class KeyGen {

    public static void main(String[] args) {
        try {
            System.out.println("🔨 Generating fresh RSA-2048 Key Pair...");

            // Generate Keys
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair pair = kpg.generateKeyPair();

            RSAPrivateCrtKey privKey = (RSAPrivateCrtKey) pair.getPrivate();
            RSAPublicKey pubKey = (RSAPublicKey) pair.getPublic();

            System.out.println("✅ Keys Generated successfully!");

            // --- 1. Prepare SERVER_SECRET.txt (For SecurityManager.java) ---
            String serverPrivKeyB64 = Base64.getEncoder().encodeToString(privKey.getEncoded());
            String serverJavaCode = "    // REPLACE IN SecurityManager.java (Server Private Key)\n" +
                    "    private static final String SERVER_PRIVATE_KEY = \"" + serverPrivKeyB64 + "\";";

            writeFile("SERVER_KEY_JAVA.txt", serverJavaCode);
            System.out.println("📄 Created SERVER_KEY_JAVA.txt");

            // --- 2. Prepare CLIENT_VAULT.txt (For hidder_vault.cpp) ---
            StringBuilder cpp = new StringBuilder();
            cpp.append("// REPLACE IN hidder_vault.cpp (RSA Key Components)\n\n");

            // We generate two distinct key pairs for security isolation:
            // 1. Server Key Pair: Used for encryption (Client encrypts data -> Server
            // decrypts).
            // 2. Client Identity Key Pair: Used for signing (Client signs data -> Server
            // verifies).
            // This ensures compromising one function doesn't automatically break the other.

            System.out.println("🔨 Generating second RSA pair for Client Identity...");
            KeyPair clientPair = kpg.generateKeyPair();
            RSAPrivateCrtKey clientPriv = (RSAPrivateCrtKey) clientPair.getPrivate();
            RSAPublicKey clientPub = (RSAPublicKey) clientPair.getPublic();

            // --- Part A: Client Identity Key (Hardcoded in Client, Known by Server) ---
            // For hidder_vault.cpp (Signing)
            cpp.append("// --- CLIENT SHARED IDENTITY KEY (For Signing) ---\n");
            cpp.append("const std::string B64_MODULUS = \"" + toB64(clientPriv.getModulus()) + "\";\n");
            cpp.append("const std::string B64_PUB_EXP = \"" + toB64(clientPriv.getPublicExponent()) + "\";\n");
            cpp.append("const std::string B64_PRIV_EXP = \"" + toB64(clientPriv.getPrivateExponent()) + "\";\n");
            cpp.append("const std::string B64_PRIME1 = \"" + toB64(clientPriv.getPrimeP()) + "\";\n");
            cpp.append("const std::string B64_PRIME2 = \"" + toB64(clientPriv.getPrimeQ()) + "\";\n");
            cpp.append("const std::string B64_EXP1 = \"" + toB64(clientPriv.getPrimeExponentP()) + "\";\n");
            cpp.append("const std::string B64_EXP2 = \"" + toB64(clientPriv.getPrimeExponentQ()) + "\";\n");
            cpp.append("const std::string B64_COEFF = \"" + toB64(clientPriv.getCrtCoefficient()) + "\";\n\n");

            // --- Part B: Server Key (Hardcoded Public part in Client) ---
            // For hidder_vault.cpp (Encryption)
            cpp.append("// --- SERVER PUBLIC KEY (For Encryption) ---\n");
            cpp.append("const std::string SRV_B64_MODULUS = \"" + toB64(privKey.getModulus()) + "\";\n");
            cpp.append("const std::string SRV_B64_EXP = \"" + toB64(privKey.getPublicExponent()) + "\";\n");

            writeFile("CLIENT_KEYS_CPP.txt", cpp.toString());
            System.out.println("📄 Created CLIENT_KEYS_CPP.txt");

            // --- 3. Prepare SERVER_PUBLIC_KEY for Verification (SecurityManager.java) ---
            // Server needs to verification key (Client Public Key)
            String clientPubB64 = Base64.getEncoder().encodeToString(clientPub.getEncoded()); // X.509

            // Prepare the Client Public Key for the Server to verify signatures

            String serverVerifyJava = "    // REPLACE IN SecurityManager.java (Client Public Key for Verification)\n" +
                    "    private static final String DEFAULT_PUBLIC_KEY = \"" + clientPubB64 + "\";";

            writeFile("SERVER_VERIFY_KEY.txt", serverVerifyJava);
            System.out.println("📄 Created SERVER_VERIFY_KEY.txt");

            System.out.println("\n🎉 DONE! Generated 2 Key Pairs (Client Identity & Server Secret).");
            System.out.println("[1] Copy SERVER_KEY_JAVA.txt -> SecurityManager.java (SERVER_PRIVATE_KEY)");
            System.out.println("[2] Copy SERVER_VERIFY_KEY.txt -> SecurityManager.java (DEFAULT_PUBLIC_KEY)");
            System.out.println("[3] Copy CLIENT_KEYS_CPP.txt -> hidder_vault.cpp (constants area)");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String toB64(BigInteger bigInt) {
        // Helper to encode BigInteger to Base64 (Standard Java encoding)
        return Base64.getEncoder().encodeToString(bigInt.toByteArray());
    }

    private static void writeFile(String filename, String content) throws IOException {
        try (PrintWriter out = new PrintWriter(filename)) {
            out.println(content);
        }
    }
}
