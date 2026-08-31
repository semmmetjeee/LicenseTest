package nl.semmetje.licensetest.license;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class LicenseManager {
    private static final long RECHECK_TICKS = 20L * 60L * 5L; // 5 minutes
    private static final int MAX_NETWORK_FAILURES = 3;

    private final JavaPlugin plugin;
    private final String productId;
    private final String endpoint;
    private final Gson gson = new Gson();
    private final AtomicInteger consecutiveNetworkFailures = new AtomicInteger();

    private volatile boolean valid;
    private volatile String configuredKey;

    public LicenseManager(JavaPlugin plugin, String productId, String endpoint) {
        this.plugin = plugin;
        this.productId = productId;
        this.endpoint = endpoint;
    }

    public boolean isValid() {
        return valid;
    }

    public boolean validateBeforeEnable() {
        try {
            File licenseFile = new File(plugin.getDataFolder(), "license.yml");
            if (!licenseFile.exists()) {
                plugin.getDataFolder().mkdirs();
                try (InputStream in = plugin.getResource("license.yml")) {
                    if (in == null) throw new IllegalStateException("Bundled license.yml missing");
                    Files.copy(in, licenseFile.toPath());
                }
                plugin.getLogger().severe("license.yml created. Add your license key and restart the server.");
                return false;
            }

            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(licenseFile);
            String key = yaml.getString("license-key", "").trim();
            if (key.isBlank() || key.equalsIgnoreCase("PUT-YOUR-LICENSE-HERE")) {
                plugin.getLogger().severe("No license key configured in license.yml.");
                return false;
            }

            configuredKey = key;
            ValidationResult result = validateOnline();
            if (result.type == ResultType.VALID) {
                valid = true;
                plugin.getLogger().info("License validated for product " + productId + ".");
                return true;
            }

            valid = false;
            plugin.getLogger().severe("License rejected: " + result.message);
            return false;
        } catch (Exception ex) {
            valid = false;
            plugin.getLogger().severe("Could not validate license: " + ex.getMessage());
            return false;
        }
    }

    public void startMonitoring() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!plugin.isEnabled()) return;

            ValidationResult result = validateOnline();
            if (result.type == ResultType.VALID) {
                consecutiveNetworkFailures.set(0);
                return;
            }

            if (result.type == ResultType.REJECTED) {
                valid = false;
                plugin.getLogger().severe("==================================================");
                plugin.getLogger().severe("LICENSE BECAME INVALID WHILE THE PLUGIN WAS RUNNING");
                plugin.getLogger().severe("Reason: " + result.message);
                plugin.getLogger().severe("LicenseTest is being disabled immediately.");
                plugin.getLogger().severe("==================================================");
                disableOnMainThread();
                return;
            }

            int failures = consecutiveNetworkFailures.incrementAndGet();
            plugin.getLogger().warning("License server could not be reached (" + failures + "/" + MAX_NETWORK_FAILURES + "): " + result.message);
            if (failures >= MAX_NETWORK_FAILURES) {
                valid = false;
                plugin.getLogger().severe("License verification failed repeatedly. Disabling LicenseTest as a safety measure.");
                disableOnMainThread();
            }
        }, RECHECK_TICKS, RECHECK_TICKS);
    }

    private void disableOnMainThread() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (plugin.isEnabled()) {
                plugin.getServer().getPluginManager().disablePlugin(plugin);
            }
        });
    }

    private ValidationResult validateOnline() {
        try {
            if (configuredKey == null || configuredKey.isBlank()) {
                return new ValidationResult(ResultType.REJECTED, "No configured license key");
            }

            JsonObject request = new JsonObject();
            request.addProperty("license_key", configuredKey);
            request.addProperty("product_id", productId);
            request.addProperty("instance_id", loadOrCreateInstanceId());
            request.addProperty("fingerprint", machineFingerprint());
            request.addProperty("plugin_version", plugin.getPluginMeta().getVersion());

            HttpURLConnection con = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout((int) Duration.ofSeconds(8).toMillis());
            con.setReadTimeout((int) Duration.ofSeconds(8).toMillis());
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Accept", "application/json");
            con.setRequestProperty("User-Agent", plugin.getName() + "/" + plugin.getPluginMeta().getVersion());

            byte[] body = gson.toJson(request).getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = con.getOutputStream()) {
                out.write(body);
            }

            int status = con.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? con.getInputStream() : con.getErrorStream();
            String response;
            try (stream) {
                response = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }

            JsonObject json = null;
            try {
                if (!response.isBlank()) json = gson.fromJson(response, JsonObject.class);
            } catch (Exception ignored) {
            }

            if (json != null && json.has("valid") && json.get("valid").getAsBoolean()) {
                return new ValidationResult(ResultType.VALID, json.has("message") ? json.get("message").getAsString() : "License valid");
            }

            if (status >= 400 && status < 500) {
                String reason = json != null && json.has("message") ? json.get("message").getAsString() : "HTTP " + status;
                return new ValidationResult(ResultType.REJECTED, reason);
            }

            if (status >= 200 && status < 300) {
                String reason = json != null && json.has("message") ? json.get("message").getAsString() : "License response rejected";
                return new ValidationResult(ResultType.REJECTED, reason);
            }

            return new ValidationResult(ResultType.NETWORK_ERROR, "HTTP " + status);
        } catch (Exception ex) {
            return new ValidationResult(ResultType.NETWORK_ERROR, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private String loadOrCreateInstanceId() throws Exception {
        File file = new File(plugin.getDataFolder(), ".instance");
        if (file.exists()) {
            String existing = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
            if (!existing.isBlank()) return existing;
        }
        String id = UUID.randomUUID().toString();
        Files.writeString(file.toPath(), id, StandardCharsets.UTF_8);
        return id;
    }

    private String machineFingerprint() throws Exception {
        List<String> parts = new ArrayList<>();
        parts.add(System.getProperty("os.name", ""));
        parts.add(System.getProperty("os.arch", ""));
        parts.add(System.getProperty("user.name", ""));

        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces != null) {
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                byte[] mac = ni.getHardwareAddress();
                if (mac != null) parts.add(HexFormat.of().formatHex(mac));
            }
        }

        Collections.sort(parts);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8)));
    }

    private enum ResultType {
        VALID,
        REJECTED,
        NETWORK_ERROR
    }

    private record ValidationResult(ResultType type, String message) {
    }
}
