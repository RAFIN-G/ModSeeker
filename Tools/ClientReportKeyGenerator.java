// SPDX-FileCopyrightText: 2026 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

public final class ClientReportKeyGenerator {
    private static final int[] OBF_KEY = {
            0x3D, 0x91, 0x27, 0x6C, 0xB4, 0x0A, 0xF2, 0x58,
            0x1E, 0x83, 0x4F, 0xD6, 0x7A, 0x2B, 0xC9, 0x60
    };

    private ClientReportKeyGenerator() {
    }

    public static void main(String[] args) {
        try {
            Path toolsDirectory = Path.of(".").toAbsolutePath().normalize();
            Path nativeOutput = args.length == 0
                    ? toolsDirectory.resolve("../../Hidder/native/client_keys.generated.inc").normalize()
                    : Path.of(args[0]).toAbsolutePath().normalize();
            Path publicOutput = args.length < 2
                    ? toolsDirectory.resolve("CLIENT_PUBLIC_KEY.txt")
                    : Path.of(args[1]).toAbsolutePath().normalize();
            if (Files.exists(nativeOutput) || Files.exists(publicOutput)) {
                throw new IllegalStateException("Key output already exists; refusing to replace an identity");
            }

            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) pair.getPrivate();
            RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();

            StringBuilder nativeKeys = new StringBuilder();
            nativeKeys.append(encodedArray("B64_MODULUS", privateKey.getModulus()));
            nativeKeys.append(encodedArray("B64_PUB_EXP", privateKey.getPublicExponent()));
            nativeKeys.append(encodedArray("B64_PRIV_EXP", privateKey.getPrivateExponent()));
            nativeKeys.append(encodedArray("B64_PRIME1", privateKey.getPrimeP()));
            nativeKeys.append(encodedArray("B64_PRIME2", privateKey.getPrimeQ()));
            nativeKeys.append(encodedArray("B64_EXP1", privateKey.getPrimeExponentP()));
            nativeKeys.append(encodedArray("B64_EXP2", privateKey.getPrimeExponentQ()));
            nativeKeys.append(encodedArray("B64_COEFF", privateKey.getCrtCoefficient()));

            String publicKeyValue = Base64.getEncoder().encodeToString(publicKey.getEncoded());
            String publicKeySource = "public static final String CLIENT_PUBLIC_KEY = \""
                    + publicKeyValue + "\";\n";
            boolean nativeCreated = false;
            boolean publicCreated = false;
            try {
                Files.createDirectories(nativeOutput.getParent());
                Files.writeString(nativeOutput, nativeKeys, StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                nativeCreated = true;
                Files.createDirectories(publicOutput.getParent());
                Files.writeString(publicOutput, publicKeySource, StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                publicCreated = true;
            } catch (Exception exception) {
                if (publicCreated) Files.deleteIfExists(publicOutput);
                if (nativeCreated) Files.deleteIfExists(nativeOutput);
                throw exception;
            }

            System.out.println("Generated: " + nativeOutput);
            System.out.println("Generated: " + publicOutput);
            System.out.println("StartCustomBuild.ps1 installs the generated public key automatically.");
        } catch (Exception exception) {
            System.err.println("Key generation failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static String encodedArray(String name, BigInteger value) {
        byte[] base64 = Base64.getEncoder().encode(value.toByteArray());
        StringBuilder result = new StringBuilder();
        result.append("static const unsigned char ").append(name).append("_ENC[] = {\n");
        for (int index = 0; index < base64.length; index++) {
            if (index % 12 == 0) {
                result.append("    ");
            }
            int encoded = (base64[index] & 0xFF) ^ OBF_KEY[index % OBF_KEY.length];
            result.append(String.format("0x%02X", encoded));
            if (index + 1 < base64.length) {
                result.append(',');
            }
            result.append(index % 12 == 11 || index + 1 == base64.length ? '\n' : ' ');
        }
        return result.append("};\n").toString();
    }
}
