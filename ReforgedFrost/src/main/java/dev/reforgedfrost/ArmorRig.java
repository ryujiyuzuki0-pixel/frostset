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
import org.bukkit.World;
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
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
        World world = loc.getWorld();

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
            double wx = loc.getX() + right * -cos + fwd * -sin;
            double wz = loc.getZ() + right * -sin + fwd * cos;
            double wy = loc.getY() + up;

            float yaw = part.kind == Kind.HEAD ? headYaw : st.bodyYaw;
            float pitch = part.kind == Kind.HEAD ? loc.getPitch() : 0f;
            Location at = new Location(world, wx, wy, wz, yaw, pitch);

            if (d == null || !d.isValid() || d.getWorld() != world || d.getLocation().distanceSquared(at) > 64) {
                if (d != null) d.remove();
                d = spawn(p, part, at);
                st.displays.put(part, d);
                st.sig.remove(part);
            } else {
                Location cur = d.getLocation();
                if (Math.abs(cur.getX() - wx) > 1e-4 || Math.abs(cur.getY() - wy) > 1e-4
                        || Math.abs(cur.getZ() - wz) > 1e-4
                        || Math.abs(wrap(cur.getYaw() - yaw)) > 0.1f || Math.abs(cur.getPitch() - pitch) > 0.1f) {
                    d.teleport(at);
                }
            }

            // local rotation / translation for this part
            float rotX = 0f, spin = 0f, bob = 0f;
            switch (part.kind) {
                case ARM -> rotX = part.swing * swing - (float) lean;
                case LEG -> rotX = part.swing * swing;
                case UPPER -> rotX = -(float) lean;
                case DUST -> {
                    rotX = -(float) lean;
                    if (dustAnim) {
                        spin = (float) (st.age * 0.02);
                        bob = (float) (Math.sin(st.age * 0.07) * 0.04);
                    }
                }
                default -> { }
            }
            float[] sg = st.sig.get(part);
            if (sg == null || Math.abs(sg[0] - rotX) > 0.002f || Math.abs(sg[1] - spin) > 0.002f
                    || Math.abs(sg[2] - bob) > 0.0005f) {
                st.sig.put(part, new float[] {rotX, spin, bob});
                Quaternionf q = new Quaternionf().rotateX(rotX).rotateY(spin);
                d.setTransformation(new Transformation(new Vector3f(0f, bob, 0f), q,
                        new Vector3f(0.9375f, 0.9375f, 0.9375f), new Quaternionf()));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private ItemDisplay spawn(Player owner, Part part, Location at) {
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
            e.setTeleportDuration(2);
            e.setShadowRadius(0f);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(0.9375f, 0.9375f, 0.9375f), new Quaternionf()));
        });
        // first person: the helmet would sit right in your face, so the wearer does not see their own head piece
        if (part == Part.HEAD) owner.hideEntity(plugin, d);
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
