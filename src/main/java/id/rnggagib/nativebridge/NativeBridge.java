package id.rnggagib.nativebridge;

import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class NativeBridge {
    private final Logger logger;
    private final File baseDir;
    private boolean loaded;

    public NativeBridge(Logger logger, File baseDir) {
        this.logger = logger;
        this.baseDir = baseDir;
    }

    public void tryLoad(boolean enabled, String libraryPath,
                        boolean downloadEnabled, String downloadUrl, String sha256) {
        if (!enabled) {
            logger.info("Native bridge disabled in config");
            return;
        }
        try {
            File libFile = null;
            if (libraryPath != null && !libraryPath.isBlank()) {
                File cfg = new File(libraryPath);
                libFile = cfg.isAbsolute() ? cfg : new File(baseDir, libraryPath);
            } else {
                // Auto-pick default in dataFolder/natives/<name>
                String os = System.getProperty("os.name").toLowerCase();
                String name;
                if (os.contains("win")) name = "culling_rs.dll";
                else if (os.contains("mac") || os.contains("darwin")) name = "libculling_rs.dylib";
                else name = "libculling_rs.so";
                File nativesDir = new File(baseDir, "natives");
                if (!nativesDir.exists()) {
                    if (nativesDir.mkdirs()) {
                        logger.info("Created natives directory: {}", nativesDir.getAbsolutePath());
                    }
                }
                libFile = new File(nativesDir, name);
            }
            if (!libFile.exists()) {
                if (downloadEnabled && downloadUrl != null && !downloadUrl.isBlank()) {
                    if (!attemptDownload(libFile, downloadUrl, sha256)) {
                        logger.warn("Native library download failed; continuing with Java fallback");
                        return;
                    }
                } else {
                    logger.warn("Native library not found at {}. Place your built library here or set features.native.library.", libFile.getAbsolutePath());
                    return;
                }
            }
            logger.info("Attempting to load native from {}", libFile.getAbsolutePath());
            System.load(libFile.getAbsolutePath());
            loaded = true;
            logger.info("Native bridge loaded from {}", libFile.getAbsolutePath());
        } catch (Throwable t) {
            loaded = false;
            logger.warn("Failed to load native bridge: {}", t.toString());
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    private boolean attemptDownload(File target, String url, String sha256) {
        try {
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                byte[] body = response.body();
                if (sha256 != null && !sha256.isBlank()) {
                    String got = hashSha256Hex(body);
                    if (!got.equalsIgnoreCase(sha256)) {
                        logger.warn("SHA-256 mismatch for downloaded native. expected={}, got={}", sha256, got);
                        return false;
                    }
                }
                if (target.getParentFile() != null && !target.getParentFile().exists()) {
                    target.getParentFile().mkdirs();
                }
                Files.write(target.toPath(), body);
                logger.info("Downloaded native library to {}", target.getAbsolutePath());
                return true;
            } else {
                logger.warn("Failed to download native library. HTTP {}", response.statusCode());
                return false;
            }
        } catch (IOException | InterruptedException e) {
            logger.warn("Error downloading native library: {}", e.toString());
            return false;
        }
    }

    private static String hashSha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(data);
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
