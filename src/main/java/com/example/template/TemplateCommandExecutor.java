package com.example.template;

import com.valleyrealm.valleycert.CertificateData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TemplateCommandExecutor implements CommandExecutor {

    private final TemplatePlugin plugin;

    public TemplateCommandExecutor(TemplatePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("template.admin")) {
            player.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "cert":
                showCertificate(player);
                break;
            case "encrypt":
                handleEncrypt(player, args);
                break;
            case "validate":
                handleValidate(player);
                break;
            default:
                sendUsage(player);
                break;
        }

        return true;
    }

    private void showCertificate(Player player) {
        CertificateData cert = plugin.getValleyCert().getCertificate();
        if (cert == null) {
            player.sendMessage("§cNo certificate loaded.");
            return;
        }

        player.sendMessage("§a=== Certificate Info ===");
        player.sendMessage("§ePlugin ID: §f" + cert.getPluginId());
        player.sendMessage("§eCertificate ID: §f" + cert.getCertificateId());
        player.sendMessage("§eIssuer: §f" + cert.getIssuer());
        player.sendMessage("§eStatus: §f" + cert.getStatus());
        player.sendMessage("§eIssued: §f" + cert.getIssuanceDate());
        player.sendMessage("§eExpires: §f" + cert.getExpirationDate());
        player.sendMessage("§eCapabilities: §f" + cert.getCapabilities());
    }

    private void handleEncrypt(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /template encrypt <message>");
            return;
        }

        String message = args[1];
        try {
            CryptoHelper.EncryptedContainer encrypted = plugin.getCryptoHelper().encrypt(message);
            player.sendMessage("§a=== Encrypted Data ===");
            player.sendMessage("§eSession Key: §f" + encrypted.encryptedSessionKey().substring(0, 20) + "...");
            player.sendMessage("§eIV: §f" + encrypted.iv());
            player.sendMessage("§ePayload: §f" + encrypted.encryptedPayload().substring(0, 20) + "...");
            player.sendMessage("§7Use /template validate to check certificate status.");
        } catch (Exception e) {
            player.sendMessage("§cEncryption failed: " + e.getMessage());
            plugin.getLogger().warning("Encryption error: " + e.getMessage());
        }
    }

    private void handleValidate(Player player) {
        boolean valid = plugin.getValleyCert().isInitialized();
        if (valid) {
            player.sendMessage("§aCertificate is valid and initialized.");
        } else {
            player.sendMessage("§cCertificate is not valid or not initialized.");
        }
    }

    private void sendUsage(Player player) {
        player.sendMessage("§e[Template Plugin] Commands:");
        player.sendMessage("§e/template cert §7- Show certificate info");
        player.sendMessage("§e/template encrypt <msg> §7- Encrypt a message");
        player.sendMessage("§e/template validate §7- Validate certificate");
    }
}
