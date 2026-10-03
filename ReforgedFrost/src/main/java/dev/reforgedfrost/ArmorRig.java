package dev.reforgedfrost;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the armor set as one ItemDisplay per body part (head, torso, dust, arms, hips, legs, boots)
 * and animates them every tick:
 *  - arms and legs swing while the player walks / runs (opposite arm and leg together)
 *  - the upper body leans forward while sneaking
 *  - the dust cubes around the chest slowly orbit and bob
 * Only the parts of pieces that are actually worn are shown, so a half set looks like a half set.
 * Every part is mounted on the player as a passenger, so the client moves it together with the player model
 * (server-side teleporting always trails behind your own view). Position and rotation are therefore expressed
 * purely through the display transformation: the entity itself never rotates, so the transformation frame is the world frame.
 * Models come from the resource pack (assets/mythicarmor/items/reforged_frost_armor_part_*.json).
 */
public final class ArmorRig implements Listener {

    /** One model pixel in blocks. The player model is rendered at 0.9375, so 32 px = 1.8 blocks. */
    private static final float K = 0.9375f / 16f;
    private static final float HIP_Y = 12f;

    private enum Kind { HEAD, UPPER, DUST, ARM, HIPS, LEG }

    /** x/y/z = pivot in model pixels (same numbers as tools/build_pack.py). swing = limb swing direction. */
    private enum Part {
        HEAD(FrostItems.Piece.HELMET, "head", 0, 24, 0, Kind.HEAD, 0),
        TORSO(FrostItems.Piece.CHESTPLATE, "torso", 0, 24, 0, Kind.UPPER, 0),
        DUST(FrostItems.Piece.CHESTPLATE, "dust", 0, 20, 0, Kind.DUST, 0),
        ARM_R(FrostItems.Piece.CHESTPLATE, "arm_r", 5, 22, 0, Kind.ARM, -1),
        ARM_L(FrostItems.Piece.CHESTPLATE, "arm_l", -5, 22, 0, Kind.ARM, 1),
        HIPS(FrostItems.Piece.LEGGINGS, "hips", 0, 12, 0, Kind.HIPS, 0),
        LEG_R(FrostItems.Piece.LEGGINGS, "leg_r", 2, 12, 0, Kind.LEG, 1),
        LEG_L(FrostItems.Piece.LEGGINGS, "leg_l", -2, 12, 0, Kind.LEG, -1),
        BOOT_R(FrostItems.Piece.BOOTS, "boot_r", 2, 12, 0, Kind.LEG, 1),
        BOOT_L(FrostItems.Piece.BOOTS, "boot_l", -2, 12, 0, Kind.LEG, -1);

        final FrostItems.Piece piece;
        final NamespacedKey model;
        final float x, y, z;
        final Kind kind;
        final int swing;

        Part(FrostItems.Piece piece, String id, float x, float y, float z, Kind kind, int swing) {
            this.piece = piece;
            this.model = NamespacedKey.fromString("mythicarmor:reforged_frost_armor_part_" + id);
            this.x = x;
            this.y = y;
            this.z = z;
            this.kind = kind;
            this.swing = swing;
        }

        boolean upper() {
            return kind == Kind.HEAD || kind == Kind.UPPER || kind == Kind.DUST || kind == Kind.ARM;
        }
    }

    private static final class State {
        final Map<Part, ItemDisplay> displays = new EnumMap<>(Part.class);
        final Map<Part, float[]> sig = new EnumMap<>(Part.class);
        Set<FrostItems.Piece> worn = EnumSet.noneOf(FrostItems.Piece.class);
        Location prev;
        float bodyYaw;
        double phase;
        float amp;
        long age;
    }

    private final ReforgedFrostPlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<Part, ItemStack> stacks = new EnumMap<>(Part.class);
    /** Players who flipped the default for seeing their own helmet (see toggleOwnHelmet). */
    private final Set<UUID> flippedHelmet = new java.util.HashSet<>();
    private BukkitTask task;

    public ArmorRig(ReforgedFrostPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (State st : states.values()) removeAll(st);
        states.clear();
    }

    private boolean ownHelmetShown(Player p) {
        boolean shownByDefault = !plugin.getConfig().getBoolean("animation.hide-own-helmet", true);
        return shownByDefault ^ flippedHelmet.contains(p.getUniqueId());
    }

    /** /frost helmet: show or hide your own helmet for yourself (others always see it). Returns true if now visible. */
    public boolean toggleOwnHelmet(Player p) {
        if (!flippedHelmet.remove(p.getUniqueId())) flippedHelmet.add(p.getUniqueId());
        boolean shown = ownHelmetShown(p);
        State st = states.get(p.getUniqueId());
        ItemDisplay d = st == null ? null : st.displays.get(Part.HEAD);
        if (d != null) {
            if (shown) p.showEntity(plugin, d);
            else p.hideEntity(plugin, d);
        }
        return shown;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        flippedHelmet.remove(event.getPlayer().getUniqueId());
        State st = states.remove(event.getPlayer().getUniqueId());
        if (st != null) removeAll(st);
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            State st = states.get(p.getUniqueId());
            Set<FrostItems.Piece> worn = st != null && st.age % 4 != 0 ? st.worn : wornPieces(p);
            if (st == null) {
                if (worn.isEmpty()) continue;
                st = new State();
                st.bodyYaw = p.getLocation().getYaw();
                states.put(p.getUniqueId(), st);
            }
            st.worn = worn;
            if (worn.isEmpty() || !shouldShow(p)) {
                removeAll(st);
                st.prev = null;
                if (worn.isEmpty()) states.remove(p.getUniqueId());
                continue;
            }
            update(p, st);
        }
        states.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private static boolean shouldShow(Player p) {
        if (!p.isValid() || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return false;
        if (p.hasPotionEffect(PotionEffectType.INVISIBILITY)) return false;
        Pose pose = p.getPose();
        // swimming, gliding, sleeping... use poses the rig does not animate, so the parts would float detached
        return pose == Pose.STANDING || pose == Pose.SNEAKING;
    }

    private static Set<FrostItems.Piece> wornPieces(Player p) {
        Set<FrostItems.Piece> out = EnumSet.noneOf(FrostItems.Piece.class);
        var inv = p.getInventory();
        if (SetBonus.isPiece(inv.getHelmet(), FrostItems.Piece.HELMET)) out.add(FrostItems.Piece.HELMET);
        if (SetBonus.isPiece(inv.getChestplate(), FrostItems.Piece.CHESTPLATE)) out.add(FrostItems.Piece.CHESTPLATE);
        if (SetBonus.isPiece(inv.getLeggings(), FrostItems.Piece.LEGGINGS)) out.add(FrostItems.Piece.LEGGINGS);
        if (SetBonus.isPiece(inv.getBoots(), FrostItems.Piece.BOOTS)) out.add(FrostItems.Piece.BOOTS);
        return out;
    }

    // ------------------------------------------------------------------ per-player update

    private void update(Player p, State st) {
        st.age++;
        boolean animate = plugin.getConfig().getBoolean("animation.enabled", true);
        double swingDeg = plugin.getConfig().getDouble("animation.swing-degrees", 40.0);
        double leanDeg = plugin.getConfig().getDouble("animation.sneak-lean-degrees", 26.0);
        boolean dustAnim = animate && plugin.getConfig().getBoolean("animation.dust", true);

        Location loc = p.getLocation();
        double speed = 0;
        if (st.prev != null && st.prev.getWorld() == loc.getWorld()) {
            double dx = loc.getX() - st.prev.getX(), dz = loc.getZ() - st.prev.getZ();
            speed = Math.sqrt(dx * dx + dz * dz);
        }
        st.prev = loc.clone();
        boolean moving = speed > 0.02 && !p.isInsideVehicle();

        // body yaw: follows the head while moving, otherwise only when the head turns too far (like vanilla)
        float headYaw = loc.getYaw();
        if (moving) {
            st.bodyYaw += wrap(headYaw - st.bodyYaw) * 0.5f;
        } else {
            float diff = wrap(headYaw - st.bodyYaw);
            if (diff > 75f) st.bodyYaw = headYaw - 75f;
            else if (diff < -75f) st.bodyYaw = headYaw + 75f;
        }

        // limb swing
        float target = animate && moving ? (float) Math.min(1.0, speed / 0.25) : 0f;
        st.amp += (target - st.amp) * 0.35f;
        if (moving) st.phase += Math.min(speed * 4.0, 1.0);
        float swing = (float) (Math.cos(st.phase * 0.6662) * Math.toRadians(swingDeg) * st.amp);

        boolean sneaking = p.getPose() == Pose.SNEAKING;
        double lean = sneaking ? Math.toRadians(leanDeg) : 0.0;

        double yawR = Math.toRadians(st.bodyYaw);
        double sin = Math.sin(yawR), cos = Math.cos(yawR);

        // a passenger sits at the vehicle's origin + its current height (1.8 standing, 1.5 sneaking)
        float attach = (float) p.getHeight();
        boolean hideOwn = !ownHelmetShown(p);
        float yawB = (float) Math.toRadians(st.bodyYaw);
        float yawH = (float) Math.toRadians(headYaw);
        float pitch = (float) Math.toRadians(loc.getPitch());

        for (Part part : Part.values()) {
            ItemDisplay d = st.displays.get(part);
            if (!st.worn.contains(part.piece)) {
                if (d != null) {
                    d.remove();
                    st.displays.remove(part);
                    st.sig.remove(part);
                }
                continue;
            }
            if (d == null || !d.isValid() || d.getVehicle() != p) {
                if (d != null) d.remove();
                d = spawn(p, part, loc, hideOwn);
                if (d == null) {
                    st.displays.remove(part);
                    continue;
                }
                st.displays.put(part, d);
                st.sig.remove(part);
            }

            // pivot position, leaning the upper body forward around the hips when sneaking
            double px = part.x, py = part.y, pz = part.z;
            if (lean != 0 && part.upper()) {
                double f = -pz, u = py - HIP_Y;
                double f2 = f * Math.cos(lean) + u * Math.sin(lean);
                double u2 = u * Math.cos(lean) - f * Math.sin(lean);
                pz = -f2;
                py = HIP_Y + u2;
            }
            double right = px * K, fwd = -pz * K, up = py * K;
            // offset from the player's feet in world space (right = (-cos,-sin), forward = (-sin,cos) in x/z)
            float tx = (float) (right * -cos + fwd * -sin);
            float tz = (float) (right * -sin + fwd * cos);
            float ty = (float) up - attach;

            // The item display flips the model 180 deg, so inside the transformation the model front is +z:
            // rotX > 0 tips the top forward (lean) and swings a hanging limb backward.
            float rotX = 0f, spin = 0f, bob = 0f;
            switch (part.kind) {
                case ARM -> rotX = -part.swing * swing + (float) lean;
                case LEG -> rotX = -part.swing * swing;
                case UPPER -> rotX = (float) lean;
                case DUST -> {
                    rotX = (float) lean;
                    if (dustAnim) {
                        spin = (float) (st.age * 0.02);
                        bob = (float) (Math.sin(st.age * 0.07) * 0.04);
                    }
                }
                default -> { }
            }
            ty += bob;
            float yaw = part.kind == Kind.HEAD ? yawH : yawB;
            float pit = part.kind == Kind.HEAD ? pitch : 0f;

            float[] now = {tx, ty, tz, yaw, pit, rotX, spin};
            float[] sg = st.sig.get(part);
            boolean changed = sg == null;
            if (!changed) {
                for (int i = 0; i < now.length; i++) {
                    if (Math.abs(now[i] - sg[i]) > 0.0015f) {
                        changed = true;
                        break;
                    }
                }
            }
            if (changed) {
                st.sig.put(part, now);
                Quaternionf q = new Quaternionf().rotateY(-yaw).rotateX(pit + rotX).rotateY(spin);
                d.setTransformation(new Transformation(new Vector3f(tx, ty, tz), q,
                        new Vector3f(0.9375f, 0.9375f, 0.9375f), new Quaternionf()));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private ItemDisplay spawn(Player owner, Part part, Location at, boolean hideOwnHelmet) {
        ItemStack stack = stacks.computeIfAbsent(part, k -> {
            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta meta = it.getItemMeta();
            meta.setItemModel(k.model);
            it.setItemMeta(meta);
            return it;
        });
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(stack);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setPersistent(false);
            e.setInvulnerable(true);
            e.setInterpolationDelay(0);
            e.setInterpolationDuration(2);
            e.setShadowRadius(0f);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(0.9375f, 0.9375f, 0.9375f), new Quaternionf()));
        });
        if (!owner.addPassenger(d)) {
            d.remove();
            return null;
        }
        // first person: the helmet would sit right in your face, so the wearer does not see their own head piece
        if (part == Part.HEAD && hideOwnHelmet) owner.hideEntity(plugin, d);
        return d;
    }

    private void removeAll(State st) {
        List<ItemDisplay> list = new ArrayList<>(st.displays.values());
        for (ItemDisplay d : list) d.remove();
        st.displays.clear();
        st.sig.clear();
    }

    private static float wrap(float deg) {
        deg %= 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }
}
