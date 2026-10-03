package dev.reforgedfrost;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Set bonuses:
 *  - any piece worn      -> Speed I  (config: partial-speed-amplifier)
 *  - all 4 pieces worn   -> Speed II (config: full-speed-amplifier) + damage reduction
 */
public final class SetBonus implements Listener {

    /** Effect duration in ticks; refreshed every REFRESH ticks so it never visibly runs out. */
    private static final int DURATION = 40;
    private static final int REFRESH = 10;

    private final ReforgedFrostPlugin plugin;
    private final Set<UUID> buffed = new HashSet<>();

    public SetBonus(ReforgedFrostPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, REFRESH);
    }

    public void clearAll() {
        for (UUID id : new HashSet<>(buffed)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) removeOurSpeed(p);
        }
        buffed.clear();
    }

    private void tick() {
        int partialAmp = plugin.getConfig().getInt("set-bonus.partial-speed-amplifier", 0);
        int fullAmp = plugin.getConfig().getInt("set-bonus.full-speed-amplifier", 1);

        for (Player p : Bukkit.getOnlinePlayers()) {
            int count = countPieces(p);
            if (count == 0) {
                if (buffed.remove(p.getUniqueId())) removeOurSpeed(p);
                continue;
            }
            int amp = count == 4 ? fullAmp : partialAmp;
            applySpeed(p, amp);
            buffed.add(p.getUniqueId());
        }
    }

    private void applySpeed(Player p, int amp) {
        PotionEffect existing = p.getPotionEffect(PotionEffectType.SPEED);
        // don't downgrade a stronger/longer effect from a potion or beacon
        if (existing != null && (existing.getAmplifier() > amp
                || (existing.getAmplifier() == amp && existing.getDuration() > DURATION))) {
            return;
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, DURATION, amp, true, false, true));
    }

    private void removeOurSpeed(Player p) {
        PotionEffect e = p.getPotionEffect(PotionEffectType.SPEED);
        if (e != null && e.getDuration() <= DURATION) p.removePotionEffect(PotionEffectType.SPEED);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        double reduction = plugin.getConfig().getDouble("set-bonus.full-damage-reduction", 0.05);
        if (reduction <= 0 || countPieces(p) < 4) return;
        event.setDamage(event.getDamage() * (1.0 - reduction));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (buffed.remove(event.getPlayer().getUniqueId())) removeOurSpeed(event.getPlayer());
    }

    public static int countPieces(Player p) {
        var inv = p.getInventory();
        int n = 0;
        if (isPiece(inv.getHelmet(), FrostItems.Piece.HELMET)) n++;
        if (isPiece(inv.getChestplate(), FrostItems.Piece.CHESTPLATE)) n++;
        if (isPiece(inv.getLeggings(), FrostItems.Piece.LEGGINGS)) n++;
        if (isPiece(inv.getBoots(), FrostItems.Piece.BOOTS)) n++;
        return n;
    }

    /** Matches by MythicArmors asset id, so items made by Nexo/Oraxen/ItemsAdder configs count too. */
    public static boolean isPiece(ItemStack item, FrostItems.Piece piece) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        NamespacedKey want = FrostItems.assetKey(piece);
        if (piece == FrostItems.Piece.HELMET) {
            return meta.hasItemModel() && want.equals(meta.getItemModel());
        }
        return meta.hasEquippable() && want.equals(meta.getEquippable().getModel());
    }
}
