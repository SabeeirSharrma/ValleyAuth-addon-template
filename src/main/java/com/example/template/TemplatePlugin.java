package com.example.template;

import com.valleyrealm.valleycert.ValleyCert;
import com.valleyrealm.valleycert.CertificateData;
import org.bukkit.plugin.java.JavaPlugin;

public class TemplatePlugin extends JavaPlugin {

    private ValleyCert valleyCert;
    private CryptoHelper cryptoHelper;

    @Override
    public void onEnable() {
        getLogger().info("TemplatePlugin enabling...");

        // Initialize ValleyCert
        valleyCert = new ValleyCert(getDataFolder().toPath());

        // Request certificate with required capabilities
        String pluginId = "template-plugin";
        String[] capabilities = {"VLINK", "MIGRATION_PROVIDER"};
        int requestedLifetimeDays = 90;

        boolean certObtained = valleyCert.initialize(pluginId, capabilities, requestedLifetimeDays);

        if (!certObtained) {
            getLogger().severe("Failed to obtain certificate. Plugin will be disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Validate capabilities
        if (!valleyCert.validateCapability("VLINK")) {
            getLogger().warning("Certificate missing VLINK capability.");
        }

        // Initialize crypto helper with certificate
        cryptoHelper = new CryptoHelper(valleyCert.getCertificate());

        // Register commands
        getCommand("template").setExecutor(new TemplateCommandExecutor(this));

        getLogger().info("TemplatePlugin enabled successfully.");
    }

    @Override
    public void onDisable() {
        getLogger().info("TemplatePlugin disabled.");
    }

    public ValleyCert getValleyCert() {
        return valleyCert;
    }

    public CryptoHelper getCryptoHelper() {
        return cryptoHelper;
    }
}
