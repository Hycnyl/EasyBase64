import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Properties;

// Runs on the signing runner. Arguments are the apksigner executable, input APK and output APK.
class SignRelease {
    static final String KEY_SECRET = "EASYBASE64_JKS_BASE64";
    static final String PROPS_SECRET = "EASYBASE64_STORE_PASSWORD";

    static byte[] decodedSecret(String name) {
        String encoded = System.getenv(name);
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("Missing repository Actions secret " + name);
        }
        try {
            return Base64.getDecoder().decode(encoded.replaceAll("\\s+", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Base64 in " + name);
        }
    }

    static String required(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing signing.properties field " + key);
        }
        return value;
    }

    public static void main(String[] args) throws Exception {
        Properties props = new Properties();
        // The legacy secret name holds the complete Base64-encoded Java properties file.
        byte[] config = decodedSecret(PROPS_SECRET);
        try {
            props.load(new ByteArrayInputStream(config));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Java properties encoding");
        }
        String alias = required(props, "keyAlias");
        String storePassword = required(props, "storePassword");
        String keyPassword = required(props, "keyPassword");
        Path keyFile = Files.createTempFile(Path.of(System.getenv("RUNNER_TEMP")), "easybase64-", ".jks");
        try {
            Files.write(keyFile, decodedSecret(KEY_SECRET));
            ProcessBuilder signer = new ProcessBuilder(args[0], "sign",
                "--ks", keyFile.toString(), "--ks-key-alias", alias,
                "--ks-pass", "env:EB64_STORE_PASS", "--key-pass", "env:EB64_KEY_PASS",
                "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
                "--v3-signing-enabled", "false", "--v4-signing-enabled", "false",
                "--out", args[2], args[1]);
            signer.environment().remove(KEY_SECRET);
            signer.environment().remove(PROPS_SECRET);
            signer.environment().put("EB64_STORE_PASS", storePassword);
            signer.environment().put("EB64_KEY_PASS", keyPassword);
            signer.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            signer.redirectError(ProcessBuilder.Redirect.DISCARD);
            if (signer.start().waitFor() != 0) {
                throw new IOException("apksigner could not sign with the supplied keystore, alias and passwords");
            }
        } finally {
            Files.deleteIfExists(keyFile);
        }
    }
}
