
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

            // Client Signing Key (We assume Client uses same key pair for singing or
            // distinct?
            // In the codebase logic, Client SINGS with Private Key, Server VERIFIES with
            // Public Key.
            // AND Server Encrypts with Server Public Key (Wait, logic check: usually
            // separate pairs)
            // But for simplicity in this project we often reused pairs or just need ONE
            // pair for the whole system?
            // The current code has: "CLIENT RSA Key (Signing)" and "SERVER RSA Key
            // (Encryption)".
            // Let's generate ONE pair that serves as the "System Key".
            // Client needs Private Key components (to sign) and Server Public Key
            // components (to encrypt data to server? No wait).
            // Let's look at hidder_vault.cpp:
            // Client Imports: B64_MODULUS, B64_PRIV_EXP... (This is a Private Key).
            // Client Imports: SRV_B64_MODULUS, SRV_B64_EXP (This is a Public Key).

            // To make it easy for users, we will generate ONE master pair.
            // Theoretical risk: If Client has Private Key, they can decrypt Server traffic?
            // Client USES Private Key to SIGN. Server uses Public Key to VERIFY.
            // Client USES Server Public Key to ENCRYPT. Server uses Private Key to DECRYPT.
            // So:
            // Client needs: MyPrivateKey (Full) + ServerPublicKey (Modulus/Exp)
            // Server needs: MyPrivateKey (Full) + ClientPublicKey (Modulus/Exp)
            // If we use ONE pair for everything:
            // Client has Full Key. Server has Full Key.
            // If Client has Full Key, they can decrypt what they sent? Yes.
            // Can they decrypt what OTHERS sent? Only if everyone shares the same key.
            // In this mod, EVERY client has the SAME "Client Key" hardcoded?
            // Yes, "B64_MODULUS" is hardcoded. So "Client Key" is a shared secret among all
            // clients (essentially public).
            // "Server Key" is what matters for encryption.
            // We'll generate ONE pair to be the "Server Key".
            // AND generate ONE pair to be the "Shared Client Key".
            // Actually, let's generate TWO pairs to be professional.

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

            // Wait, SecurityManager needs the Client Public Key to verify signatures.
            // "DEFAULT_PUBLIC_KEY" in SecurityManager.java

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
        // Remove sign byte if present (BigInteger representation) usually handled by
        // C++ vault but cleaner here?
        // The C++ vault has "Strip leading zero" logic. Java BigInteger.toByteArray()
        // adds a zero byte if MSB is set.
        // Base64 encoding that byte is fine, C++ handles it.
        // But for neatness, let's keep it standard.
        return Base64.getEncoder().encodeToString(bigInt.toByteArray());
    }

    private static void writeFile(String filename, String content) throws IOException {
        try (PrintWriter out = new PrintWriter(filename)) {
            out.println(content);
        }
    }
}
