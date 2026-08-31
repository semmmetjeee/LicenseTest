package nl.semmetje.licensetest;

import nl.semmetje.licensetest.license.LicenseManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class LicenseTestPlugin extends JavaPlugin {
    private LicenseManager licenseManager;

    @Override
    public void onLoad() {
        licenseManager = new LicenseManager(this, "licensetest", "https://semmmetje.nl/api/license/validate.php");
        if (!licenseManager.validateBeforeEnable()) {
            getLogger().severe("License validation failed. The plugin will remain disabled.");
        }
    }

    @Override
    public void onEnable() {
        if (licenseManager == null || !licenseManager.isValid()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        getLogger().info("License valid. LicenseTest enabled.");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("discord")) {
            sender.sendMessage(getConfig().getString("discord-url", "https://discord.gg/example"));
            return true;
        }
        return false;
    }
}
