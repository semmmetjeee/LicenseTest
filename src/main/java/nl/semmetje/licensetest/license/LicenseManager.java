package nl.semmetje.licensetest.license;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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

public final class LicenseManager {
    private final JavaPlugin plugin;
    private final String productId;
    private final String endpoint;
    private final Gson gson = new Gson();
    private volatile boolean valid;

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

            String instanceId = loadOrCreateInstanceId();
            String fingerprint = machineFingerprint();

            JsonObject request = new JsonObject();
            request.addProperty("license_key", key);
            request.addProperty("product_id", productId);
            request.addProperty("instance_id", instanceId);
            request.addProperty("fingerprint", fingerprint);
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

            JsonObject json = gson.fromJson(response, JsonObject.class);
            if (status != 200 || json == null || !json.has("valid") || !json.get("valid").getAsBoolean()) {
                String reason = json != null && json.has("message") ? json.get("message").getAsString() : "HTTP " + status;
                plugin.getLogger().severe("License rejected: " + reason);
                return false;
            }

            valid = true;
            plugin.getLogger().info("License validated for product " + productId + ".");
            return true;
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not validate license: " + ex.getMessage());
            return false;
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
}
