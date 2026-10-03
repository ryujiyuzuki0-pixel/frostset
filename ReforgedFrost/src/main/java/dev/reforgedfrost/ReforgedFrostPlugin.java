package dev.reforgedfrost;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.ZipFile;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.minimessage.MiniMessage;

public final class ReforgedFrostPlugin extends JavaPlugin implements Listener {

    private static final String MODEL_RESOURCE = "models/reforged_frost_armor.bbmodel";

    private PackHost packHost;
    private SetBonus setBonus;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        extractBundledPack();

        if (Bukkit.getPluginManager().getPlugin("MythicArmors") == null) {
            if (!resolvePackFile().isFile()) {
                getLogger().warning("MythicArmors not found and no pack.zip available. Items can be given, "
                        + "but there is no texture pack to send. Install MythicArmors, or put a generated "
                        + "pack.zip in plugins/ReforgedFrost/ (or bundle it as src/main/resources/pack.zip).");
            } else {
                getLogger().info("MythicArmors not found, using " + resolvePackFile().getPath());
            }
        } else if (getConfig().getBoolean("install-model", true)) {
            installModel();
        }

        PluginCommand cmd = getCommand("frost");
        if (cmd != null) {
            FrostCommand handler = new FrostCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        startPackHost();
        Bukkit.getPluginManager().registerEvents(this, this);

        setBonus = new SetBonus(this);
        Bukkit.getPluginManager().registerEvents(setBonus, this);
        setBonus.start();
    }

    @Override
    public void onDisable() {
        if (setBonus != null) setBonus.clearAll();
        if (packHost != null) {
            packHost.stop();
            packHost = null;
        }
    }

    /** Re-read config and restart the pack host. */
    public void reload() {
        reloadConfig();
        if (packHost != null) {
            packHost.stop();
            packHost = null;
        }
        startPackHost();
    }

    /** /frost status - tells you exactly which link in the chain is broken. */
    public void status(CommandSender s) {
        File plugins = getDataFolder().getParentFile();
        var ma = Bukkit.getPluginManager().getPlugin("MythicArmors");
        line(s, "MythicArmors", ma == null
                ? "<yellow>not installed - using the built-in pack (helmet is 3D, chest/legs/boots are flat armor layers)"
                : "<green>installed (" + ma.getPluginMeta().getVersion() + ")");

        File model = new File(plugins, "MythicArmors/models/reforged_frost_armor.bbmodel");
        line(s, "Model file", model.isFile()
                ? "<green>present (" + model.length() / 1024 + " KB)"
                : "<red>missing - run /frost rebuild or restart");

        File pack = resolvePackFile();
        if (externalPackUrl() != null) {
            line(s, "Pack mode", "<green>external URL (no port needed): " + externalPackUrl());
        }
        if (!pack.isFile()) {
            line(s, "pack.zip", "<red>missing - MythicArmors has not generated it and none is bundled. Run /frost rebuild and check console for errors");
        } else {
            int hits = 0;
            try (ZipFile z = new ZipFile(pack)) {
                hits = (int) z.stream().filter(e -> e.getName().contains("reforged_frost")).count();
            } catch (IOException e) {
                line(s, "pack.zip", "<red>unreadable: " + e.getMessage());
            }
            line(s, "pack.zip", (hits > 0 ? "<green>" : "<red>") + pack.length() / 1024 + " KB, "
                    + hits + " reforged_frost files inside" + (hits > 0 ? "" : " (model NOT in pack)"));
        }

        line(s, "Pack host", packHost == null ? "<yellow>disabled" : (packHost.running() ? "<green>" + packHost.describe() : "<red>not running"));
        line(s, "Your armor pieces", s instanceof org.bukkit.entity.Player p
                ? SetBonus.countPieces(p) + "/4 worn" : "n/a");
        s.sendMessage(MiniMessage.miniMessage().deserialize(
                "<gray>Client needs Minecraft 1.21.4+ and Server Resource Packs set to Enabled/Prompt."));
    }

    private static void line(CommandSender s, String k, String v) {
        s.sendMessage(MiniMessage.miniMessage().deserialize("<#C8C4E0>" + k + ": <white>" + v));
    }

    public PackHost packHost() {
        return packHost;
    }

    /**
     * Which pack.zip to serve: the one MythicArmors generated if it exists (always freshest),
     * otherwise plugins/ReforgedFrost/pack.zip (bundled in the jar or dropped in by hand).
     */
    public File resolvePackFile() {
        // external mode: the URL serves the pack bundled in the jar, so hash that exact file
        if (externalPackUrl() != null) return new File(getDataFolder(), "pack.zip");
        File ma = new File(getDataFolder().getParentFile(), getConfig().getString("pack.file", "MythicArmors/pack.zip"));
        if (ma.isFile()) return ma;
        return new File(getDataFolder(), "pack.zip");
    }

    /** pack.external-url if it is set to a real URL (not blank / placeholder), else null. */
    public String externalPackUrl() {
        String u = getConfig().getString("pack.external-url", "");
        if (u == null) return null;
        u = u.trim();
        if (u.isEmpty() || u.contains("REPO_SLUG") || !u.startsWith("http")) return null;
        return u;
    }

    /** If the jar ships a pack.zip (src/main/resources/pack.zip), unpack it next to the config. */
    private void extractBundledPack() {
        try (InputStream in = getResource("pack.zip")) {
            if (in == null) return;
            byte[] bundled = in.readAllBytes();
            File out = new File(getDataFolder(), "pack.zip");
            if (out.isFile() && Arrays.equals(Files.readAllBytes(out.toPath()), bundled)) return;
            Files.createDirectories(getDataFolder().toPath());
            Files.write(out.toPath(), bundled);
            getLogger().info("Installed bundled pack.zip (" + bundled.length / 1024 + " KB).");
        } catch (IOException e) {
            getLogger().severe("Failed to extract bundled pack: " + e.getMessage());
        }
    }

    private void startPackHost() {
        if (!getConfig().getBoolean("pack.enabled", false)) return;
        packHost = new PackHost(this, this::resolvePackFile);
        try {
            packHost.start(getConfig().getInt("pack.port", 8123));
        } catch (IOException e) {
            getLogger().severe("Could not start pack host: " + e.getMessage());
            packHost = null;
        }
    }

    private void installModel() {
        File target = new File(getDataFolder().getParentFile(), "MythicArmors/models/reforged_frost_armor.bbmodel");
        try (InputStream in = getResource(MODEL_RESOURCE)) {
            if (in == null) {
                getLogger().severe("Bundled model missing from jar.");
                return;
            }
            byte[] bundled = in.readAllBytes();
            if (target.isFile() && Arrays.equals(Files.readAllBytes(target.toPath()), bundled)) return;

            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), bundled);
            getLogger().info("Installed reforged_frost_armor.bbmodel into MythicArmors/models.");

            // Wait until the server finished loading, then rebuild the pack.
            Bukkit.getScheduler().runTask(this, () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ma reload"));
        } catch (IOException e) {
            getLogger().severe("Failed to install model: " + e.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (packHost != null) {
            // small delay so the client is fully in the world
            Bukkit.getScheduler().runTaskLater(this, () -> packHost.send(event.getPlayer()), 20L);
        }
    }
}
