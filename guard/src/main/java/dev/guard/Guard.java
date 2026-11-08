package dev.guard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Projectile;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.scheduler.BukkitTask;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Date;
import java.util.LinkedHashSet;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

/**
 * Lightweight anticheat for Paper 1.21+.
 *
 * Design rule: every threshold is deliberately generous and every check uses a buffer,
 * because a missed cheater is better than a punished legit player.
 * Default punishment = staff alert + setback. Auto-kick is OFF.
 */
public final class Guard extends JavaPlugin implements Listener {

    // ---------------- tunables ----------------
    private static final double MAX_PING = 400;          // skip checks above this ping (ms)
    private static final double MIN_TPS = 18.0;          // skip checks when the server is lagging
    private static final double BASE_SPEED = 0.65;       // blocks/packet; vanilla sprint-jump is ~0.36
    private static final double REACH = 4.0;             // vanilla is 3.0, extra room for latency
    private static final double REACH_CREATIVE = 6.5;
    private static final double SETBACK_VL = 4;          // violation level that triggers a setback
    private static final boolean AUTO_KICK = false;      // leave off until you trust the numbers
    private static final double KICK_VL = 20;

    private static final int SUPPORT = 1, SPECIAL = 2, SLIPPERY = 4, CEILING = 8;

    private final Map<UUID, Data> data = new HashMap<>();

    private record FlagEntry(long time, UUID id, String name, String check, double vl, String info, Location loc) { }
    private final Deque<FlagEntry> log = new ArrayDeque<>();   // last 300 alerts, in memory only
    private final Set<UUID> alertsOff = new HashSet<>();
    private final Set<UUID> frozen = new HashSet<>();

    // relog method / combat log
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, Long> lastCombatLog = new HashMap<>();
    private final Map<UUID, Deque<Long>> combatLogs = new HashMap<>();
    private record SavedVl(Map<String, Double> vl, long time) { }
    private final Map<UUID, SavedVl> savedVl = new HashMap<>();

    // entity culling (anti-ESP / anti-radar / anti-freecam)
    private final Map<UUID, Set<UUID>> culled = new HashMap<>();
    private final Map<UUID, Map<UUID, Integer>> occludedCount = new HashMap<>();
    private int cullSlice;

    // client scans
    private final Set<UUID> scanning = new HashSet<>();
    private final Map<String, String> xrayWorlds = new LinkedHashMap<>();

    private static final class Data {
        Location lastSafe;
        int airTicks;
        double lastDy;            // vertical speed of the previous airborne packet
        boolean lastAir;
        long lastMoveMs, hoverSince;
        long exemptUntil, slipperyUntil;
        boolean ignoreNextTp;
        final double[] yaw = new double[3];
        int yawIdx;
        final Map<String, Double> buf = new HashMap<>();
        final Map<String, Double> vl = new HashMap<>();
        final Map<String, Long> lastAlert = new HashMap<>();
        int mined, veins;      // xray statistics window
        Location lastVein;
        String brand;          // what the client says it is (vanilla, fabric, ...)
        final Set<String> channels = new HashSet<>();
        int aimStreak;
    }

    // ---------------- config (embedded; written to plugins/Guard/config.yml on first start) ----------------

    private static final String DEFAULT_CONFIG = """
# =====================================================================
#  GOLDRUSH GUARD - the one and only config file.
#  Edit, then restart the server. Run /acstatus in game to see what is active.
# =====================================================================

detection:
  ban-enabled: false        # safer default: staff review only, no automatic bans
  ban-duration: 15d         # how long a detected cheat client is banned for
  keybind-check:
    enabled: true
    on-join: true           # runs a few seconds after joining (an anvil screen flashes for a moment)
    delay-seconds: 5
    ban: false              # staff review only; enable only after extensive testing
    # Keybind IDs of known hacked clients. A normal client shows the ID back unchanged,
    # a client with that mod installed shows the bound key instead. Keep each ID under 45 characters.
    keybinds:
      - "key.meteor-client.open-gui"
      - "key.meteor-client.open-commands"
  # Ban when the client brand (what the client calls itself) contains one of these words, lowercase.
  brands: []
  # Ban when the client registers one of these plugin channels, e.g. "wdl:init" (World Downloader).
  channels: []

combat:
  tag-seconds: 15           # fighting another player tags you for this long
  log-punishment: kill      # kill | none : what happens when someone logs out while tagged

relog:
  remember-minutes: 10      # a quick relog does not wipe a player's violation levels
  window-seconds: 30        # rejoining this soon after a combat log is flagged as a relog

anti-freecam:
  enabled: true
  max-block-interaction-distance: 6.0 # repeated remote block actions are flagged; camera movement itself cannot be proven

entity-culling:
  enabled: true             # hides occluded player entities only; does not hide blocks/chunks or guarantee freecam prevention
  min-distance: 16          # closer players are always shown
  max-distance: 80
  interval-ticks: 5         # how often each player's view is rechecked (lower = smoother, heavier)
""";

    private void ensureConfig() {
        getDataFolder().mkdirs();
        File f = new File(getDataFolder(), "config.yml");
        if (!f.exists()) {
            try {
                Files.writeString(f.toPath(), DEFAULT_CONFIG, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                getLogger().severe("Could not write config.yml: " + ex.getMessage());
            }
        }
        reloadConfig();
    }

    @Override
    public void onEnable() {
        ensureConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::hoverCheck, 10L, 10L);
        for (String c : List.of("sus", "alerts", "freeze", "acstatus")) {
            if (getCommand(c) != null) getCommand(c).setExecutor(this);
        }
        // violation levels decay by 1 every 30 seconds
        Bukkit.getScheduler().runTaskTimer(this, () ->
                data.values().forEach(d -> d.vl.replaceAll((k, v) -> Math.max(0, v - 1))), 600L, 600L);
        Bukkit.getScheduler().runTaskTimer(this, this::cullTick, 20L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, this::cleanupMemory, 1200L, 1200L);
        auditAntiXray();
        getLogger().info("Guard enabled.");
    }

    @Override
    public void onDisable() {
        for (Map.Entry<UUID, Set<UUID>> e : culled.entrySet()) {
            Player viewer = Bukkit.getPlayer(e.getKey());
            if (viewer == null) continue;
            for (UUID t : e.getValue()) {
                Player target = Bukkit.getPlayer(t);
                if (target != null) viewer.showEntity(this, target);
            }
        }
    }

    private Data data(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> {
            Data d = new Data();
            SavedVl saved = savedVl.remove(k);
            long keep = getConfig().getLong("relog.remember-minutes", 10) * 60_000L;
            // a relog must not reset violation levels
            if (saved != null && System.currentTimeMillis() - saved.time() < keep) d.vl.putAll(saved.vl());
            return d;
        });
    }

    // ---------------- movement checks ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Data d = data(p);
        Location from = e.getFrom(), to = e.getTo();
        if (to == null) return;

        // remember recent rotation speed for the aim check (rotation-only packets land here too)
        d.yaw[d.yawIdx++ % 3] = Math.abs(wrap(to.getYaw() - from.getYaw()));

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        if (dx == 0 && dy == 0 && dz == 0) return;

        long now = System.currentTimeMillis();
        d.lastMoveMs = now;
        int f = scan(to);
        boolean support = (f & SUPPORT) != 0;
        if ((f & SLIPPERY) != 0) d.slipperyUntil = now + 1500;

        if (exempt(p, d, now) || (f & SPECIAL) != 0) {
            d.airTicks = 0;
            d.lastAir = false;
            d.lastDy = dy;
            if (support) d.lastSafe = to.clone();
            return;
        }

        if (support) {
            d.airTicks = 0;
            d.lastSafe = to.clone();
        } else {
            d.airTicks++;
        }

        // GRAVITY: in mid-air, vertical speed must follow vanilla physics: next = (last - 0.08) * 0.98.
        // Hovering, steady climbing, constant-speed descent and tiny anti-kick dips all break this.
        double predicted = (d.lastDy - 0.08) * 0.98;
        boolean badGravity = !support && d.lastAir && (f & CEILING) == 0 && Math.abs(dy - predicted) > 0.04;
        d.lastAir = !support;
        d.lastDy = dy;
        if (suspicious(d, "gravity", badGravity, 4, 0.5)) {
            flag(p, d, "Fly", 1, String.format("gravity dy=%.3f expected=%.3f", dy, predicted), true);
        }

        // FLY: still rising/hovering long after a jump should have ended, or a jump no human can make
        boolean badFly = (!support && d.airTicks > 12 && dy > -0.1) || dy > 0.62;
        if (suspicious(d, "fly", badFly, 3, 0.25)) {
            flag(p, d, "Fly", 1, String.format("air=%d dy=%.3f", d.airTicks, dy), true);
        }

        // SPEED: horizontal distance per move packet, with generous limits
        double limit = BASE_SPEED * (p.getWalkSpeed() / 0.2);
        PotionEffect speed = p.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) limit *= 1 + 0.2 * (speed.getAmplifier() + 1);
        double hd = Math.hypot(dx, dz);
        boolean badSpeed = now >= d.slipperyUntil && hd > limit;
        if (suspicious(d, "speed", badSpeed, 5, 0.5)) {
            flag(p, d, "Speed", 1, String.format("%.3f > %.3f", hd, limit), true);
        }
    }

    private boolean exempt(Player p, Data d, long now) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return true;
        if (p.hasPermission("anticheat.bypass")) return true;
        if (p.getAllowFlight() || p.isFlying() || p.isGliding() || p.isRiptiding()
                || p.isInsideVehicle() || p.isDead()) return true;
        if (now < d.exemptUntil) return true;
        if (p.hasPotionEffect(PotionEffectType.LEVITATION)
                || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                || p.hasPotionEffect(PotionEffectType.JUMP_BOOST)) return true;
        return lagging(p);
    }

    private boolean lagging(Player p) {
        return p.getPing() > MAX_PING || Bukkit.getTPS()[0] < MIN_TPS;
    }

    /** Looks at the blocks around the player's hitbox. Returns SUPPORT / SPECIAL / SLIPPERY bit flags. */
    private int scan(Location l) {
        World w = l.getWorld();
        double x = l.getX(), y = l.getY(), z = l.getZ();
        int result = 0;
        int x0 = floor(x - 0.3), x1 = floor(x + 0.3);
        int z0 = floor(z - 0.3), z1 = floor(z + 0.3);
        int yBottom = floor(y - 0.6), yBody = floor(y + 1.8), yTop = floor(y + 2.1), yFeet = floor(y);

        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                if (!w.isChunkLoaded(bx >> 4, bz >> 4)) continue;
                for (int by = yBottom; by <= yTop; by++) {
                    Block b = w.getBlockAt(bx, by, bz);
                    Material m = b.getType();
                    if (m.isAir()) continue;

                    if (by <= yBody && special(b, m)) result |= SPECIAL;
                    if (Tag.ICE.isTagged(m) || m == Material.SOUL_SAND
                            || m == Material.SOUL_SOIL || m == Material.SLIME_BLOCK) result |= SLIPPERY;

                    if (by >= floor(y + 1.7) && (result & CEILING) == 0) {
                        for (BoundingBox bb : b.getCollisionShape().getBoundingBoxes()) {
                            double bottom = by + bb.getMinY();
                            if (bottom >= y + 1.7 && bottom <= y + 2.1) {
                                result |= CEILING;
                                break;
                            }
                        }
                    }
                    if (by <= yFeet && (result & SUPPORT) == 0) {
                        for (BoundingBox bb : b.getCollisionShape().getBoundingBoxes()) {
                            double top = by + bb.getMaxY();
                            if (top <= y + 0.1 && top >= y - 0.6) {
                                result |= SUPPORT;
                                break;
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    /** Blocks that change movement rules in ways we do not try to model. */
    private static boolean special(Block b, Material m) {
        if (b.isLiquid() || Tag.CLIMBABLE.isTagged(m) || Tag.BEDS.isTagged(m)) return true;
        switch (m) {
            case COBWEB, HONEY_BLOCK, SLIME_BLOCK, POWDER_SNOW, SCAFFOLDING,
                 BUBBLE_COLUMN, SWEET_BERRY_BUSH, LILY_PAD -> {
                return true;
            }
            default -> { }
        }
        return b.getBlockData() instanceof Waterlogged w && w.isWaterlogged();
    }

    // ---------------- combat checks ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getEntity() instanceof LivingEntity target)) return;
        if (p.hasPermission("anticheat.bypass") || p.getGameMode() == GameMode.SPECTATOR || lagging(p)) return;

        Data d = data(p);
        Location eye = p.getEyeLocation();
        Vector ev = eye.toVector();
        BoundingBox box = target.getBoundingBox();
        Vector closest = new Vector(
                clamp(ev.getX(), box.getMinX(), box.getMaxX()),
                clamp(ev.getY(), box.getMinY(), box.getMaxY()),
                clamp(ev.getZ(), box.getMinZ(), box.getMaxZ()));
        double dist = ev.distance(closest);

        // REACH
        double max = p.getGameMode() == GameMode.CREATIVE ? REACH_CREATIVE : REACH;
        if (suspicious(d, "reach", dist > max, 2, 0.5)) {
            flag(p, d, "Reach", 1, String.format("%.2f > %.2f", dist, max), false);
        }

        // KILLAURA / FOV: hitting something far outside where you are looking
        Vector dir = eye.getDirection();
        double fov = 0;
        if (dist > 0.01) fov = Math.toDegrees(dir.angle(closest.clone().subtract(ev)));
        if (suspicious(d, "aura", fov > 75, 3, 0.5)) {
            flag(p, d, "KillAura", 1, String.format("fov=%.0f", fov), false);
        }

        // AIM SNAP: big flick right before the hit AND crosshair dead-centre on the target,
        // several hits in a row (any normal hit resets progress)
        double aimErr = Math.toDegrees(dir.angle(box.getCenter().subtract(ev)));
        double flick = Math.max(d.yaw[0], Math.max(d.yaw[1], d.yaw[2]));
        boolean snapLock = flick > 50 && aimErr < 2.0 && dist > 1.5;
        if (suspicious(d, "aim", snapLock, 4, 1.0)) {
            flag(p, d, "Aim", 1, String.format("flick=%.0f err=%.1f", flick, aimErr), false);
        }

        // AIM PERFECT: a camera that is moving, yet lands on the exact centre of another player hit after hit
        boolean perfect = target instanceof Player && flick > 8 && aimErr < 1.2 && dist > 2.5;
        d.aimStreak = perfect ? d.aimStreak + 1 : 0;
        if (d.aimStreak >= 6) {
            d.aimStreak = 3;
            flag(p, d, "AimPerfect", 1, String.format("6+ centred hits in a row, err=%.1f", aimErr), false);
        }
    }

    // ---------------- remote block-action / freecam heuristics ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return;
        checkRemoteBlockAction(p, e.getClickedBlock(), "interact");
    }

    /**
     * A freecam client can move its camera away from the server-side player, but it cannot
     * legitimately interact with a block outside the server's reach. This is a heuristic:
     * it flags repeated remote block actions, not camera movement itself.
     */
    private void checkRemoteBlockAction(Player p, Block block, String action) {
        if (!getConfig().getBoolean("anti-freecam.enabled", true)
                || p.hasPermission("anticheat.bypass") || lagging(p)) return;
        double max = getConfig().getDouble("anti-freecam.max-block-interaction-distance", 6.0);
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        double distance = p.getEyeLocation().distance(center);
        Data d = data(p);
        if (suspicious(d, "freecam", distance > max, 2, 0.5)) {
            flag(p, d, "Freecam", 1,
                    String.format("remote block %s at %.2f blocks (limit %.2f)", action, distance, max), false);
        }
    }

    // ---------------- xray statistics (alert-only; real protection is Paper's anti-xray) ----------------

    private static final java.util.Set<Material> VALUABLE = java.util.EnumSet.of(
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, Material.ANCIENT_DEBRIS,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);

    /**
     * Counts distinct valuable-ore veins found per stone-like blocks mined.
     * 5+ veins within 150 stone blocks is far above normal luck. Never auto-punishes:
     * it only tells staff who to spectate.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if ((p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE)
                || p.hasPermission("anticheat.bypass")) return;
        Block b = e.getBlock();
        checkRemoteBlockAction(p, b, "break");
        if (p.getGameMode() != GameMode.SURVIVAL) return;
        Data d = data(p);
        Material m = b.getType();

        if (Tag.BASE_STONE_OVERWORLD.isTagged(m) || Tag.BASE_STONE_NETHER.isTagged(m)) {
            d.mined++;
        } else if (VALUABLE.contains(m)) {
            Location loc = b.getLocation();
            boolean sameVein = d.lastVein != null
                    && d.lastVein.getWorld().equals(loc.getWorld())
                    && d.lastVein.distanceSquared(loc) <= 36; // within 6 blocks = same vein
            if (!sameVein) d.veins++;
            d.lastVein = loc;
            if (d.veins >= 5 && d.mined <= 150) {
                flag(p, d, "Xray", 1, String.format("%d veins in %d stone", d.veins, d.mined), false);
                d.veins = 0;
                d.mined = 0;
            }
        }
        if (d.mined > 150) {
            d.mined = 0;
            d.veins = 0;
        }
    }

    // ---------------- grace periods (things that legitimately move players) ----------------

    private void grace(Player p, long ms) {
        Data d = data(p);
        d.exemptUntil = Math.max(d.exemptUntil, System.currentTimeMillis() + ms);
        d.airTicks = 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Data d = data(e.getPlayer());
        d.airTicks = 0;
        if (d.ignoreNextTp) { // our own setback: don't hand out a free grace period
            d.ignoreNextTp = false;
            return;
        }
        d.exemptUntil = System.currentTimeMillis() + 1500;
        d.lastSafe = e.getTo().clone();
    }

    @EventHandler public void onVelocity(PlayerVelocityEvent e) { grace(e.getPlayer(), 2000); }
    @EventHandler public void onRiptide(PlayerRiptideEvent e) { grace(e.getPlayer(), 3000); }
    @EventHandler public void onJoin(PlayerJoinEvent e) { grace(e.getPlayer(), 3000); }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) { grace(e.getPlayer(), 3000); }
    @EventHandler public void onGameMode(PlayerGameModeChangeEvent e) { grace(e.getPlayer(), 3000); }
    @EventHandler public void onToggleFlight(PlayerToggleFlightEvent e) { grace(e.getPlayer(), 3000); }

    @EventHandler
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.getEntity() instanceof Player p) grace(p, 3000);
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent e) {
        if (e.getExited() instanceof Player p) grace(p, 1500);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        if (frozen.remove(id)) notifyStaff("&c" + p.getName() + " logged out while frozen!");

        Data d = data.get(id);
        Long tagged = combatUntil.remove(id);
        boolean kicked = e.getReason() == PlayerQuitEvent.QuitReason.KICKED;
        GameMode gm = p.getGameMode();
        if (tagged != null && tagged > now && !kicked && !p.isDead() && !p.hasPermission("anticheat.bypass")
                && (gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE)) {
            if (d == null) d = data(p);
            lastCombatLog.put(id, now);
            Deque<Long> logs = combatLogs.computeIfAbsent(id, k -> new ArrayDeque<>());
            logs.addLast(now);
            while (!logs.isEmpty() && now - logs.peekFirst() > WINDOW_MS) logs.pollFirst();
            flag(p, d, "CombatLog", logs.size() >= 3 ? 6 : 3,
                    "left the game during combat (" + logs.size() + " in 30 min)", false);
            if ("kill".equalsIgnoreCase(getConfig().getString("combat.log-punishment", "kill"))) {
                p.setHealth(0.0);
                Bukkit.broadcast(Component.text(p.getName() + " logged out in combat and was killed.",
                        net.kyori.adventure.text.format.NamedTextColor.RED));
            }
        }

        // keep violation levels for a while: relogging must not reset them
        if (d != null && !d.vl.isEmpty()) savedVl.put(id, new SavedVl(new HashMap<>(d.vl), now));
        data.remove(id);
        culled.remove(id);
        occludedCount.remove(id);
        for (Set<UUID> set : culled.values()) set.remove(id);
        scanning.remove(id);
    }

    // ---------------- flagging ----------------

    /** Buffered detection: needs repeated bad samples; good samples drain the buffer. */
    private boolean suspicious(Data d, String key, boolean bad, double limit, double decay) {
        double b = d.buf.getOrDefault(key, 0.0);
        b = bad ? b + 1 : Math.max(0, b - decay);
        boolean hit = b >= limit;
        d.buf.put(key, hit ? limit / 2 : b);
        return hit;
    }

    private void flag(Player p, Data d, String check, double add, String info, boolean setback) {
        double v = d.vl.merge(check, add, Double::sum);
        long now = System.currentTimeMillis();

        if (now - d.lastAlert.getOrDefault(check, 0L) > 1000) { // throttle alert spam
            d.lastAlert.put(check, now);
            log.addLast(new FlagEntry(now, p.getUniqueId(), p.getName(), check, v, info, p.getLocation().clone()));
            while (log.size() > 300) log.pollFirst();
            String msg = String.format("\u00a7c[AC] \u00a7f%s \u00a77failed \u00a7e%s \u00a77(vl %.1f) %s",
                    p.getName(), check, v, info);
            getLogger().info(ChatColor.stripColor(msg));
            Component alert = LegacyComponentSerializer.legacySection().deserialize(msg)
                    .clickEvent(ClickEvent.runCommand("/sus tp " + p.getName()))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to teleport to " + p.getName())));
            for (Player s : Bukkit.getOnlinePlayers()) {
                if (s.hasPermission("anticheat.alerts") && !alertsOff.contains(s.getUniqueId())) s.sendMessage(alert);
            }
        }

        if (setback && v >= SETBACK_VL && d.lastSafe != null) {
            Location back = d.lastSafe.clone();
            d.airTicks = 0;
            Bukkit.getScheduler().runTask(this, () -> {
                if (!p.isOnline()) return;
                d.ignoreNextTp = true;
                p.teleport(back);
            });
        }

        if (AUTO_KICK && v >= KICK_VL) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (p.isOnline()) p.kick(net.kyori.adventure.text.Component.text("Unfair advantage detected."));
            });
        }
    }

    // ---------------- staff commands ----------------

    private static final long WINDOW_MS = 30 * 60_000L;
    private static final Map<String, Integer> WEIGHT = Map.of(
            "CheatClient", 100, "Xray", 6, "AimPerfect", 5, "Relog", 5, "KillAura", 4,
            "Aim", 4, "Reach", 3, "Fly", 3, "CombatLog", 3, "Speed", 2);

    private static final class Sus {
        String name = "?";
        int total;
        int risk;
        long last;
        Location lastLoc;
        final Map<String, Integer> checks = new LinkedHashMap<>();
    }

    private record ChunkKey(String world, int x, int z) { }

    private static final class ChunkSus {
        int flags;
        int risk;
        long last;
        Location lastLoc;
        final Set<String> players = new LinkedHashSet<>();
        final Map<String, Integer> checks = new LinkedHashMap<>();
    }

    private static String riskLabel(int r) {
        if (r >= 100) return "&4&lCRITICAL";
        if (r >= 25) return "&cHIGH";
        if (r >= 10) return "&6MEDIUM";
        return "&eLOW";
    }

    private static String where(Location l) {
        if (l == null || l.getWorld() == null) return "unknown";
        return l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()
                + " (chunk " + (l.getBlockX() >> 4) + ", " + (l.getBlockZ() >> 4) + ")";
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        switch (c.getName().toLowerCase(Locale.ROOT)) {
            case "sus" -> cmdSus(s, a);
            case "alerts" -> cmdAlerts(s);
            case "freeze" -> cmdFreeze(s, a);
            case "acstatus" -> cmdAcstatus(s);
            default -> { }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        String name = c.getName().toLowerCase(Locale.ROOT);
        if (name.equals("sus") && a.length == 1) {
            List<String> out = new ArrayList<>(List.of("list", "chunks", "scan", "tp", "clear"));
            out.removeIf(x -> !x.startsWith(a[0].toLowerCase(Locale.ROOT)));
            out.addAll(onlineNames(a[0]));
            return out;
        }
        if (name.equals("sus") && a.length == 2) return onlineNames(a[1]);
        if (name.equals("freeze") && a.length == 1) return onlineNames(a[0]);
        return List.of();
    }

    private List<String> onlineNames(String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix.toLowerCase(Locale.ROOT);
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (pl.getName().toLowerCase(Locale.ROOT).startsWith(p)) out.add(pl.getName());
        }
        return out;
    }

    private void cmdSus(CommandSender s, String[] a) {
        if (a.length == 0) {
            if (s instanceof Player p) openSusGui(p); else listSuspects(s);
            return;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> listSuspects(s);
            case "chunks" -> {
                if (s instanceof Player p) openChunkGui(p); else listChunks(s);
            }
            case "scan" -> {
                if (a.length < 2) { say(s, "&cUsage: /sus scan <player>"); return; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { say(s, "&c" + a[1] + " is not online."); return; }
                say(s, "&7Scanning &f" + t.getName() + " &7for hacked-client keybinds (a manual scan never bans)...");
                startKeybindScan(t, s, 0);
            }
            case "tp", "clear" -> {
                if (a.length < 2) { say(s, "&cUsage: /sus " + sub + " <player>"); return; }
                if (sub.equals("tp")) tpSuspect(s, a[1]); else clearSuspect(s, a[1]);
            }
            default -> showSuspect(s, a[0]);
        }
    }

    // ---- who is suspicious ----

    /** Everyone flagged in the last 30 minutes (online or not), highest risk first. */
    private List<Map.Entry<UUID, Sus>> sortedSuspects(long now) {
        Map<UUID, Sus> map = new HashMap<>();
        for (FlagEntry f : log) {
            if (now - f.time() > WINDOW_MS) continue;
            Sus x = map.computeIfAbsent(f.id(), k -> new Sus());
            x.name = f.name();
            x.total++;
            x.risk += WEIGHT.getOrDefault(f.check(), 2);
            if (f.time() >= x.last) {
                x.last = f.time();
                x.lastLoc = f.loc();
            }
            x.checks.merge(f.check(), 1, Integer::sum);
        }
        List<Map.Entry<UUID, Sus>> list = new ArrayList<>(map.entrySet());
        list.sort((x, y) -> y.getValue().risk != x.getValue().risk
                ? Integer.compare(y.getValue().risk, x.getValue().risk)
                : Integer.compare(y.getValue().total, x.getValue().total));
        return list;
    }

    /** Chunks where flags keep happening (xray tunnels, fly over a base, fights...). */
    private List<Map.Entry<ChunkKey, ChunkSus>> sortedChunks(long now) {
        Map<ChunkKey, ChunkSus> map = new HashMap<>();
        for (FlagEntry f : log) {
            Location l = f.loc();
            if (now - f.time() > WINDOW_MS || l == null || l.getWorld() == null) continue;
            ChunkKey k = new ChunkKey(l.getWorld().getName(), l.getBlockX() >> 4, l.getBlockZ() >> 4);
            ChunkSus c = map.computeIfAbsent(k, x -> new ChunkSus());
            c.flags++;
            c.risk += WEIGHT.getOrDefault(f.check(), 2);
            c.players.add(f.name());
            c.checks.merge(f.check(), 1, Integer::sum);
            if (f.time() >= c.last) {
                c.last = f.time();
                c.lastLoc = l;
            }
        }
        List<Map.Entry<ChunkKey, ChunkSus>> list = new ArrayList<>(map.entrySet());
        list.sort((x, y) -> Integer.compare(y.getValue().risk, x.getValue().risk));
        return list;
    }

    private void listSuspects(CommandSender s) {
        long now = System.currentTimeMillis();
        List<Map.Entry<UUID, Sus>> list = sortedSuspects(now);
        if (list.isEmpty()) { say(s, "&aNo suspicious activity in the last 30 minutes."); return; }
        say(s, "&cSuspects, last 30 min &7(click a name for details)");
        int i = 0;
        for (Map.Entry<UUID, Sus> e : list) {
            if (i++ >= 10) break;
            Sus x = e.getValue();
            boolean online = Bukkit.getPlayer(e.getKey()) != null;
            StringBuilder sb = new StringBuilder();
            x.checks.forEach((k, v) -> sb.append(k).append(" x").append(v).append(' '));
            Component line = LegacyComponentSerializer.legacyAmpersand().deserialize(
                            "&e#" + i + " " + riskLabel(x.risk) + " &f" + x.name + (online ? " &a(online)" : " &7(offline)")
                                    + " &7- &c" + sb.toString().trim() + " &8- &7" + ago(now - x.last))
                    .clickEvent(ClickEvent.runCommand("/sus " + x.name))
                    .hoverEvent(HoverEvent.showText(Component.text("Click for details")));
            s.sendMessage(line);
        }
    }

    private void listChunks(CommandSender s) {
        long now = System.currentTimeMillis();
        List<Map.Entry<ChunkKey, ChunkSus>> list = sortedChunks(now);
        if (list.isEmpty()) { say(s, "&aNo suspicious chunks in the last 30 minutes."); return; }
        say(s, "&cSuspicious chunks, last 30 min");
        int i = 0;
        for (Map.Entry<ChunkKey, ChunkSus> e : list) {
            if (i++ >= 10) break;
            ChunkKey k = e.getKey();
            ChunkSus c = e.getValue();
            say(s, "&e#" + i + " " + riskLabel(c.risk) + " &f" + k.world() + " chunk " + k.x() + ", " + k.z()
                    + " &8- &7" + c.flags + " flags by &f" + String.join(", ", c.players));
        }
    }

    /** /sus <player> : risk, client info, relog info, current violation levels and the last flags. */
    private void showSuspect(CommandSender s, String name) {
        long now = System.currentTimeMillis();
        Player online = Bukkit.getPlayerExact(name);
        UUID id = online != null ? online.getUniqueId() : null;
        List<FlagEntry> entries = new ArrayList<>();
        for (FlagEntry f : log) {
            boolean match = id != null ? f.id().equals(id) : f.name().equalsIgnoreCase(name);
            if (match) entries.add(f);
        }
        if (entries.isEmpty() && online == null) { say(s, "&cNo flags found for " + name + "."); return; }

        UUID who = id != null ? id : entries.get(entries.size() - 1).id();
        String shown = !entries.isEmpty() ? entries.get(entries.size() - 1).name() : online.getName();
        int risk = 0;
        for (FlagEntry f : entries) {
            if (now - f.time() <= WINDOW_MS) risk += WEIGHT.getOrDefault(f.check(), 2);
        }
        say(s, riskLabel(risk) + " &f" + shown + (online != null ? " &a(online)" : " &7(offline)") + " &8- &7risk score &f" + risk);
        if (online != null) {
            say(s, "&7Ping: &f" + online.getPing() + "ms &7| World: &f" + online.getWorld().getName());
        }
        Data d = data.get(who);
        if (d != null) {
            if (d.brand != null) say(s, "&7Client brand: &f" + d.brand);
            List<String> extra = new ArrayList<>();
            for (String ch : d.channels) {
                if (!ch.startsWith("minecraft:")) extra.add(ch);
            }
            if (!extra.isEmpty()) say(s, "&7Plugin channels: &f" + String.join(", ", extra));
            if (!d.vl.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                d.vl.forEach((k, v) -> sb.append(k).append(' ').append(String.format(Locale.US, "%.1f", v)).append("  "));
                say(s, "&7Current VL: &e" + sb.toString().trim());
            } else {
                say(s, "&7Current VL: &anone");
            }
        }
        Deque<Long> logs = combatLogs.get(who);
        if (logs != null && !logs.isEmpty()) say(s, "&7Combat logs in 30 min: &c" + logs.size() + " &7(relog method)");
        if (entries.isEmpty()) { say(s, "&7No recent flags."); return; }
        for (int i = Math.max(0, entries.size() - 10); i < entries.size(); i++) {
            FlagEntry f = entries.get(i);
            say(s, "&8[" + ago(now - f.time()) + "] &e" + f.check()
                    + " &7vl " + String.format(Locale.US, "%.1f", f.vl()) + " &f" + f.info());
        }
        say(s, "&7Last flagged at: &f" + where(entries.get(entries.size() - 1).loc()));
    }

    private void teleportTo(Player staff, Location loc) {
        if (loc == null || loc.getWorld() == null) { say(staff, "&cNo location was recorded."); return; }
        staff.teleport(loc);
        say(staff, "&aTeleported to &f" + where(loc));
    }

    private void tpSuspect(CommandSender s, String name) {
        if (!(s instanceof Player staff)) { say(s, "&cPlayers only."); return; }
        Player t = Bukkit.getPlayerExact(name);
        if (t == null) { say(s, "&c" + name + " is not online."); return; }
        staff.teleport(t.getLocation());
        say(s, "&aTeleported to &f" + t.getName());
    }

    private void clearSuspect(CommandSender s, String name) {
        if (!s.hasPermission("anticheat.manage")) { say(s, "&cNo permission."); return; }
        Player t = Bukkit.getPlayerExact(name);
        UUID id = t != null ? t.getUniqueId() : null;
        if (id != null) {
            Data d = data.get(id);
            if (d != null) { d.vl.clear(); d.buf.clear(); }
            savedVl.remove(id);
        }
        log.removeIf(f -> id != null ? f.id().equals(id) : f.name().equalsIgnoreCase(name));
        say(s, "&aCleared flags for &f" + name);
    }

    private void cmdAlerts(CommandSender s) {
        if (!(s instanceof Player p)) { say(s, "&cPlayers only."); return; }
        if (!alertsOff.remove(p.getUniqueId())) alertsOff.add(p.getUniqueId());
        say(p, alertsOff.contains(p.getUniqueId()) ? "&7Alerts: &cOFF" : "&7Alerts: &aON");
    }

    private void cmdFreeze(CommandSender s, String[] a) {
        if (a.length < 1) { say(s, "&cUsage: /freeze <player>"); return; }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) { say(s, "&c" + a[0] + " is not online."); return; }
        if (frozen.remove(t.getUniqueId())) {
            say(s, "&aUnfroze &f" + t.getName());
            t.sendMessage(Component.text("You have been unfrozen.", net.kyori.adventure.text.format.NamedTextColor.GREEN));
        } else {
            frozen.add(t.getUniqueId());
            say(s, "&cFroze &f" + t.getName() + " &7(run /freeze again to release)");
            t.sendMessage(Component.text("You have been frozen by staff. Do not log out.",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrozenMove(PlayerMoveEvent e) {
        if (!frozen.contains(e.getPlayer().getUniqueId())) return;
        Location f = e.getFrom(), t = e.getTo();
        if (t == null) return;
        if (f.getX() != t.getX() || f.getY() != t.getY() || f.getZ() != t.getZ()) {
            e.setTo(new Location(f.getWorld(), f.getX(), f.getY(), f.getZ(), t.getYaw(), t.getPitch()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrozenHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && frozen.contains(p.getUniqueId())) e.setCancelled(true);
    }

    // ---- /sus menus ----

    private static final class SusGui implements InventoryHolder {
        final UUID[] ids = new UUID[54];
        final String[] names = new String[54];
        final Location[] locs = new Location[54];
        final boolean chunks;
        Inventory inv;
        SusGui(boolean chunks) { this.chunks = chunks; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static Component guiLine(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack button(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(guiLine(name));
        if (lore != null) meta.lore(lore.stream().map(Guard::guiLine).toList());
        it.setItemMeta(meta);
        return it;
    }

    /** /sus (players): one head per suspect, worst first. */
    private void openSusGui(Player staff) {
        long now = System.currentTimeMillis();
        List<Map.Entry<UUID, Sus>> list = sortedSuspects(now);
        SusGui g = new SusGui(false);
        g.inv = Bukkit.createInventory(g, 54, Component.text("Suspects - last 30 min"));

        int slot = 0;
        for (Map.Entry<UUID, Sus> e : list) {
            if (slot >= 45) break;
            Sus x = e.getValue();
            Player online = Bukkit.getPlayer(e.getKey());

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(e.getKey()));
            meta.displayName(guiLine(riskLabel(x.risk) + " &f" + x.name + " &8- &e" + x.total + " alert" + (x.total == 1 ? "" : "s")));

            List<Component> lore = new ArrayList<>();
            lore.add(guiLine("&7Risk score: &f" + x.risk));
            x.checks.forEach((k, v) -> lore.add(guiLine("&7" + k + ": &f" + v)));
            lore.add(guiLine("&7Last flag: &f" + ago(now - x.last)));
            lore.add(guiLine(online != null ? "&aOnline" : "&7Offline"));
            Data d = data.get(e.getKey());
            if (d != null && d.brand != null) lore.add(guiLine("&7Client: &f" + d.brand));
            Deque<Long> logs = combatLogs.get(e.getKey());
            if (logs != null && !logs.isEmpty()) lore.add(guiLine("&7Combat logs: &c" + logs.size()));
            lore.add(guiLine("&7Last flagged at: &f" + where(x.lastLoc)));
            lore.add(Component.empty());
            lore.add(guiLine(online != null ? "&eLeft-click: teleport to them" : "&eLeft-click: teleport to where it happened"));
            lore.add(guiLine("&eRight-click: details in chat"));
            if (staff.hasPermission("anticheat.manage")) lore.add(guiLine("&eShift-right-click: clear flags"));
            meta.lore(lore);
            head.setItemMeta(meta);
            head.setAmount(Math.max(1, Math.min(64, x.total)));

            g.inv.setItem(slot, head);
            g.ids[slot] = e.getKey();
            g.names[slot] = x.name;
            g.locs[slot] = x.lastLoc;
            slot++;
        }
        if (slot == 0) {
            g.inv.setItem(22, button(Material.LIME_STAINED_GLASS_PANE, "&aNo suspicious activity in the last 30 minutes", null));
        }
        g.inv.setItem(49, button(Material.COMPASS, "&bSuspicious chunks",
                List.of("&7Chunks where flags keep happening", "&eClick to open")));
        staff.openInventory(g.inv);
    }

    /** /sus chunks: chunks ranked by how many (and how serious) flags happened inside them. */
    private void openChunkGui(Player staff) {
        long now = System.currentTimeMillis();
        List<Map.Entry<ChunkKey, ChunkSus>> list = sortedChunks(now);
        SusGui g = new SusGui(true);
        g.inv = Bukkit.createInventory(g, 54, Component.text("Suspicious chunks - last 30 min"));

        int slot = 0;
        for (Map.Entry<ChunkKey, ChunkSus> e : list) {
            if (slot >= 45) break;
            ChunkKey k = e.getKey();
            ChunkSus c = e.getValue();
            ItemStack it = new ItemStack(c.risk >= 25 ? Material.TNT : Material.GRASS_BLOCK, Math.max(1, Math.min(64, c.flags)));
            ItemMeta meta = it.getItemMeta();
            meta.displayName(guiLine(riskLabel(c.risk) + " &f" + k.world() + " chunk " + k.x() + ", " + k.z()));
            List<Component> lore = new ArrayList<>();
            lore.add(guiLine("&7Flags here: &f" + c.flags + " &7| risk &f" + c.risk));
            lore.add(guiLine("&7Players: &f" + String.join(", ", c.players)));
            c.checks.forEach((ck, v) -> lore.add(guiLine("&7" + ck + ": &f" + v)));
            lore.add(guiLine("&7Blocks: &fx " + (k.x() << 4) + " to " + ((k.x() << 4) + 15)
                    + "&7, &fz " + (k.z() << 4) + " to " + ((k.z() << 4) + 15)));
            lore.add(guiLine("&7Last flag: &f" + ago(now - c.last)));
            lore.add(Component.empty());
            lore.add(guiLine("&eLeft-click: teleport to the last flag here"));
            meta.lore(lore);
            it.setItemMeta(meta);
            g.inv.setItem(slot, it);
            g.locs[slot] = c.lastLoc;
            slot++;
        }
        if (slot == 0) {
            g.inv.setItem(22, button(Material.LIME_STAINED_GLASS_PANE, "&aNo suspicious chunks in the last 30 minutes", null));
        }
        g.inv.setItem(49, button(Material.PLAYER_HEAD, "&bBack to suspects", null));
        staff.openInventory(g.inv);
    }

    @EventHandler
    public void onSusClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof SusGui g)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player staff)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getSlot();
        ClickType click = e.getClick();
        if (slot == 49) {
            Bukkit.getScheduler().runTask(this, () -> { if (g.chunks) openSusGui(staff); else openChunkGui(staff); });
            return;
        }
        if (slot < 0 || slot >= 54) return;
        if (g.chunks) {
            Location where = g.locs[slot];
            if (where != null && click == ClickType.LEFT) {
                Bukkit.getScheduler().runTask(this, () -> { staff.closeInventory(); teleportTo(staff, where); });
            }
            return;
        }
        if (g.ids[slot] == null) return;
        String name = g.names[slot];
        Location last = g.locs[slot];
        Bukkit.getScheduler().runTask(this, () -> {
            switch (click) {
                case LEFT -> {
                    staff.closeInventory();
                    if (Bukkit.getPlayerExact(name) != null) tpSuspect(staff, name); else teleportTo(staff, last);
                }
                case RIGHT -> { staff.closeInventory(); showSuspect(staff, name); }
                case SHIFT_RIGHT -> { clearSuspect(staff, name); openSusGui(staff); }
                default -> { }
            }
        });
    }

    @EventHandler
    public void onSusDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof SusGui) e.setCancelled(true);
    }

    // ---------------- relog method / combat log ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombatTag(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player a) attacker = a;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player shooter) attacker = shooter;
        if (attacker == null || attacker.equals(victim)) return;
        long until = System.currentTimeMillis() + getConfig().getLong("combat.tag-seconds", 15) * 1000L;
        combatUntil.put(victim.getUniqueId(), until);
        combatUntil.put(attacker.getUniqueId(), until);
    }

    @EventHandler
    public void onJoinChecks(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Data d = data(p);                      // restores violation levels from before a relog
        long now = System.currentTimeMillis();
        Long last = lastCombatLog.remove(p.getUniqueId());
        long window = getConfig().getLong("relog.window-seconds", 30) * 1000L;
        if (last != null && now - last <= window) {
            flag(p, d, "Relog", 5, "rejoined " + ((now - last) / 1000) + "s after logging out in combat", false);
        }
        long delay = Math.max(20L, getConfig().getLong("detection.keybind-check.delay-seconds", 5) * 20L);
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) clientChecks(p); }, delay);
    }

    private void cleanupMemory() {
        long now = System.currentTimeMillis();
        long keep = getConfig().getLong("relog.remember-minutes", 10) * 60_000L;
        savedVl.values().removeIf(sv -> now - sv.time() > keep);
        combatUntil.values().removeIf(t -> t < now);
        lastCombatLog.values().removeIf(t -> now - t > 600_000L);
    }

    // ---------------- cheat client detection (instant ban) ----------------

    private static boolean isBedrock(Player p) {
        return p.getUniqueId().getMostSignificantBits() == 0L;   // Floodgate/Geyser players
    }

    private static long durationMs(String s) {
        String v = s.toLowerCase(Locale.ROOT).trim();
        if (v.startsWith("perm")) return 36500L * 86_400_000L;
        try {
            long n = Long.parseLong(v.substring(0, v.length() - 1));
            return switch (v.charAt(v.length() - 1)) {
                case 'm' -> n * 60_000L;
                case 'h' -> n * 3_600_000L;
                case 'w' -> n * 604_800_000L;
                default -> n * 86_400_000L;
            };
        } catch (RuntimeException ex) {
            return 15L * 86_400_000L;
        }
    }

    /** Bans through Core's /offend (kick screen + ID); falls back to a normal timed ban. */
    private void banCheater(Player p, String why) {
        String dur = getConfig().getString("detection.ban-duration", "15d");
        notifyStaff("&4&lAUTO-BAN &f" + p.getName() + " &7(" + dur + ") &c" + why);
        getLogger().warning("AUTO-BAN " + p.getName() + " (" + dur + "): " + why);
        Bukkit.getScheduler().runTask(this, () -> {
            if (Bukkit.getPluginCommand("offend") != null) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "offend " + p.getName() + " cheating " + dur);
            }
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!p.isOnline()) return;   // /offend already removed them
                Date until = new Date(System.currentTimeMillis() + durationMs(dur));
                p.ban("Cheat software detected", until, "Guard");
                p.kick(Component.text("Cheat software detected. You are banned for " + dur + ".",
                        net.kyori.adventure.text.format.NamedTextColor.RED));
            }, 10L);
        });
    }

    /** A brand or channel match: flag it and ban. */
    private void cheatClient(Player p, String why) {
        flag(p, data(p), "CheatClient", 100, why, false);
        if (getConfig().getBoolean("detection.ban-enabled", true)) banCheater(p, "Cheat software detected: " + why);
    }

    private void clientChecks(Player p) {
        if (isBedrock(p) || p.hasPermission("anticheat.bypass")) return;
        Data d = data(p);
        String brand = p.getClientBrandName();
        d.brand = brand;
        if (brand != null) {
            String low = brand.toLowerCase(Locale.ROOT);
            for (String bad : getConfig().getStringList("detection.brands")) {
                if (!bad.isBlank() && low.contains(bad.toLowerCase(Locale.ROOT))) {
                    cheatClient(p, "client brand '" + brand + "'");
                    return;
                }
            }
        }
        if (getConfig().getBoolean("detection.keybind-check.enabled", true)
                && getConfig().getBoolean("detection.keybind-check.on-join", true)) {
            startKeybindScan(p, null, 0);
        }
    }

    @EventHandler
    public void onChannel(PlayerRegisterChannelEvent e) {
        Player p = e.getPlayer();
        String ch = e.getChannel().toLowerCase(Locale.ROOT);
        data(p).channels.add(ch);
        if (p.hasPermission("anticheat.bypass") || isBedrock(p)) return;
        for (String bad : getConfig().getStringList("detection.channels")) {
            if (!bad.isBlank() && ch.equals(bad.toLowerCase(Locale.ROOT))) {
                cheatClient(p, "plugin channel '" + ch + "'");
                return;
            }
        }
    }

    /**
     * Keybind check: opens an anvil whose item is named with a keybind component. The client turns the
     * keybind into text itself and sends that text back as the rename. A vanilla client returns the
     * key ID unchanged; a client with a hacked-client mod returns the key bound to it.
     * report == null means an automatic scan (may ban); otherwise a manual /sus scan (never bans).
     */
    private void startKeybindScan(Player target, CommandSender report, int attempt) {
        UUID id = target.getUniqueId();
        if (!target.isOnline() || isBedrock(target)) {
            if (report != null) say(report, "&cCan't scan " + target.getName() + " (offline or Bedrock).");
            return;
        }
        List<String> keys = new ArrayList<>();
        for (String k : getConfig().getStringList("detection.keybind-check.keybinds")) {
            if (!k.isBlank() && k.trim().length() <= 45) keys.add(k.trim());
        }
        if (keys.isEmpty()) {
            if (report != null) say(report, "&cNo keybinds are configured.");
            return;
        }
        if (scanning.contains(id)) {
            if (report != null) say(report, "&7Already scanning that player.");
            return;
        }
        if (target.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING) {
            if (attempt < 6) {
                Bukkit.getScheduler().runTaskLater(this, () -> startKeybindScan(target, report, attempt + 1), 100L);
            } else if (report != null) {
                say(report, "&cThat player keeps a menu open. Try again later.");
            }
            return;
        }
        InventoryView view = target.openAnvil(null, true);
        if (!(view instanceof AnvilView av)) {
            if (view != null) target.closeInventory();
            if (report != null) say(report, "&cCouldn't open the scan screen.");
            return;
        }
        scanning.add(id);
        new KeybindRun(target, av, keys, report).begin();
    }

    private final class KeybindRun implements Runnable {
        final Player target;
        final AnvilView view;
        final List<String> keys;
        final CommandSender report;
        BukkitTask task;
        int index = 0, waited = 0, inconclusive = 0;

        KeybindRun(Player target, AnvilView view, List<String> keys, CommandSender report) {
            this.target = target;
            this.view = view;
            this.keys = keys;
            this.report = report;
        }

        void begin() {
            show();
            task = Bukkit.getScheduler().runTaskTimer(Guard.this, this, 3L, 3L);
        }

        private void show() {
            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(Component.keybind(keys.get(index)));
            it.setItemMeta(meta);
            view.setItem(0, it);
            waited = 0;
        }

        private void finish(boolean detected, String key, String shown) {
            if (task != null) task.cancel();
            scanning.remove(target.getUniqueId());
            if (target.isOnline() && target.getOpenInventory().getTopInventory().getType() == InventoryType.ANVIL) {
                view.setItem(0, null);
                target.closeInventory();
            }
            if (detected) {
                keybindHit(target, key, shown, report);
            } else if (report != null) {
                say(report, "&aNo hacked-client keybinds found on &f" + target.getName() + " &7(" + keys.size()
                        + " checked" + (inconclusive > 0 ? ", " + inconclusive + " got no answer" : "") + ").");
            }
        }

        private void next(boolean noAnswer) {
            if (noAnswer) inconclusive++;
            index++;
            if (index >= keys.size()) finish(false, null, null); else show();
        }

        @Override
        public void run() {
            if (!target.isOnline() || target.getOpenInventory().getTopInventory().getType() != InventoryType.ANVIL) {
                finish(false, null, null);   // they closed it early: inconclusive, never a ban
                return;
            }
            waited++;
            String key = keys.get(index);
            String stale = index == 0 ? null : keys.get(index - 1);
            String text = view.getRenameText();
            if (text == null || text.isEmpty() || text.equals(stale)) {
                if (waited >= 10) next(true);
                return;
            }
            if (!text.equals(key)) { finish(true, key, text); return; }
            next(false);
        }
    }

    private void keybindHit(Player p, String key, String shown, CommandSender report) {
        flag(p, data(p), "CheatClient", 100, "keybind " + key + " shows as '" + shown + "'", false);
        if (report != null) {
            say(report, "&c" + p.getName() + " has a hacked client: &f" + key + " &7shows as &f" + shown
                    + "&7. Use &f/offend " + p.getName() + " cheating &7to ban.");
            return;
        }
        if (getConfig().getBoolean("detection.keybind-check.ban", true) && getConfig().getBoolean("detection.ban-enabled", true)) {
            banCheater(p, "Cheat software detected: keybind " + key);
        }
    }

    @EventHandler
    public void onScanClick(InventoryClickEvent e) {
        if (scanning.contains(e.getWhoClicked().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onScanDrag(InventoryDragEvent e) {
        if (scanning.contains(e.getWhoClicked().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onScanClose(InventoryCloseEvent e) {
        if (scanning.contains(e.getPlayer().getUniqueId()) && e.getInventory().getType() == InventoryType.ANVIL) {
            e.getView().setItem(0, null);   // never hand the scan item to the player
        }
    }

    // ---------------- entity culling: anti-ESP, anti-radar, anti-freecam ----------------

    /**
     * Players the viewer cannot actually see from where they really stand are hidden from them.
     * ESP, radars and freecam all rely on the server sending positions of players behind walls;
     * a hidden player is not sent at all. Staff (anticheat.culling.bypass) and spectators see everyone.
     */
    private void cullTick() {
        if (!getConfig().getBoolean("entity-culling.enabled", true)) return;
        int slices = Math.max(1, getConfig().getInt("entity-culling.interval-ticks", 5));
        int slice = (cullSlice++) % slices;
        double min = getConfig().getDouble("entity-culling.min-distance", 16);
        double max = getConfig().getDouble("entity-culling.max-distance", 80);
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.size() < 2) return;

        for (int i = 0; i < players.size(); i++) {
            if (i % slices != slice) continue;
            Player viewer = players.get(i);
            boolean showAll = viewer.hasPermission("anticheat.culling.bypass") || viewer.getGameMode() == GameMode.SPECTATOR;
            Map<UUID, Integer> misses = occludedCount.computeIfAbsent(viewer.getUniqueId(), k -> new HashMap<>());
            for (Player target : players) {
                if (target.equals(viewer)) continue;
                boolean show = showAll || !viewer.getWorld().equals(target.getWorld()) || target.isGlowing()
                        || (viewer.getVehicle() != null && viewer.getVehicle().equals(target.getVehicle()));
                if (!show) {
                    double d2 = viewer.getLocation().distanceSquared(target.getLocation());
                    show = d2 < min * min || d2 > max * max || visible(viewer, target);
                }
                if (show) {
                    misses.remove(target.getUniqueId());
                    reveal(viewer, target);
                } else if (misses.merge(target.getUniqueId(), 1, Integer::sum) >= 2) {   // hidden twice in a row
                    conceal(viewer, target);
                }
            }
        }
    }

    private void conceal(Player viewer, Player target) {
        Set<UUID> set = culled.computeIfAbsent(viewer.getUniqueId(), k -> new HashSet<>());
        if (set.add(target.getUniqueId())) viewer.hideEntity(this, target);
    }

    private void reveal(Player viewer, Player target) {
        Set<UUID> set = culled.get(viewer.getUniqueId());
        if (set != null && set.remove(target.getUniqueId())) viewer.showEntity(this, target);
    }

    private boolean visible(Player viewer, Player target) {
        World w = viewer.getWorld();
        Location eye = viewer.getEyeLocation();
        BoundingBox bb = target.getBoundingBox();
        double cx = bb.getCenterX(), cz = bb.getCenterZ();
        double[][] pts = {
                {cx, bb.getMinY() + 0.15, cz}, {cx, bb.getCenterY(), cz}, {cx, bb.getMaxY() - 0.1, cz},
                {bb.getMinX() + 0.05, bb.getCenterY(), bb.getMinZ() + 0.05},
                {bb.getMaxX() - 0.05, bb.getCenterY(), bb.getMaxZ() - 0.05}};
        double ex = eye.getX(), ey = eye.getY(), ez = eye.getZ();
        for (double[] pt : pts) {
            if (clear(w, ex, ey, ez, pt[0], pt[1], pt[2])) return true;
        }
        // the viewer could step sideways and peek: count that as visible so enemies never pop in late
        double[][] peeks = {{0.7, 0}, {-0.7, 0}, {0, 0.7}, {0, -0.7}};
        for (double[] o : peeks) {
            for (int i = 1; i < 3; i++) {
                if (clear(w, ex + o[0], ey, ez + o[1], pts[i][0], pts[i][1], pts[i][2])) return true;
            }
        }
        return false;
    }

    /** True when no fully solid block lies on the straight line between the two points. */
    private static boolean clear(World w, double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return true;
        int steps = (int) Math.ceil(len / 0.4);
        double sx = dx / steps, sy = dy / steps, sz = dz / steps;
        int lbx = Integer.MIN_VALUE, lby = 0, lbz = 0;
        for (int i = 1; i < steps; i++) {
            int bx = floor(x0 + sx * i), by = floor(y0 + sy * i), bz = floor(z0 + sz * i);
            if (bx == lbx && by == lby && bz == lbz) continue;
            lbx = bx;
            lby = by;
            lbz = bz;
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) continue;
            if (w.getBlockAt(bx, by, bz).getType().isOccluding()) return false;
        }
        return true;
    }

    // ---------------- anti-xray audit (Paper's chunk obfuscation) ----------------

    private void auditAntiXray() {
        xrayWorlds.clear();
        File defaults = new File(Bukkit.getWorldContainer(), "config/paper-world-defaults.yml");
        Boolean defEnabled = null;
        Boolean defPermission = null;
        int defMode = 1;
        if (defaults.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(defaults);
            defEnabled = y.getBoolean("anticheat.anti-xray.enabled", false);
            defMode = y.getInt("anticheat.anti-xray.engine-mode", 1);
            defPermission = y.getBoolean("anticheat.anti-xray.use-permission", false);
        }
        for (World w : Bukkit.getWorlds()) {
            Boolean enabled = defEnabled;
            Boolean permission = defPermission;
            int mode = defMode;
            File wf = new File(w.getWorldFolder(), "paper-world.yml");
            if (wf.exists()) {
                YamlConfiguration wy = YamlConfiguration.loadConfiguration(wf);
                if (wy.isSet("anticheat.anti-xray.enabled")) enabled = wy.getBoolean("anticheat.anti-xray.enabled");
                if (wy.isSet("anticheat.anti-xray.engine-mode")) mode = wy.getInt("anticheat.anti-xray.engine-mode");
                if (wy.isSet("anticheat.anti-xray.use-permission")) permission = wy.getBoolean("anticheat.anti-xray.use-permission");
            }
            String status;
            if (enabled == null) status = "unknown (Paper config not found)";
            else if (enabled) status = "ON (engine-mode " + mode
                    + (mode == 2 ? ", hides ores and adds fake ones" : ", hides ores")
                    + "; admin bypass permission " + (Boolean.TRUE.equals(permission) ? "ON)" : "OFF)");
            else status = "OFF";
            xrayWorlds.put(w.getName(), status);
            if (Boolean.FALSE.equals(enabled)) {
                getLogger().warning("Anti-xray is OFF in world '" + w.getName() + "'. Set anticheat.anti-xray.enabled: true "
                        + "and engine-mode: 2 in config/paper-world-defaults.yml, then restart.");
            }
            if (Boolean.TRUE.equals(enabled) && !Boolean.TRUE.equals(permission)) {
                getLogger().warning("Anti-xray is enabled in world '" + w.getName()
                        + "' but use-permission is OFF. Set anticheat.anti-xray.use-permission: true "
                        + "to allow paper.antixray.bypass for admins only.");
            }
        }
    }

    private void cmdAcstatus(CommandSender s) {
        auditAntiXray();
        say(s, "&6&lGuard status");
        boolean ban = getConfig().getBoolean("detection.ban-enabled", true);
        say(s, "&7Cheat-client ban: " + (ban ? "&aON &7(" + getConfig().getString("detection.ban-duration", "15d") + ")" : "&cOFF"));
        say(s, "&7Keybind check: " + (getConfig().getBoolean("detection.keybind-check.enabled", true) ? "&aON" : "&cOFF")
                + " &7(" + getConfig().getStringList("detection.keybind-check.keybinds").size() + " keybinds, "
                + (getConfig().getBoolean("detection.keybind-check.ban", true) ? "bans" : "alert only") + ")");
        say(s, "&7Banned client brands / channels: &f" + getConfig().getStringList("detection.brands").size()
                + " &7/ &f" + getConfig().getStringList("detection.channels").size());
        say(s, "&7Remote-block / freecam heuristic: "
                + (getConfig().getBoolean("anti-freecam.enabled", true) ? "&aON" : "&cOFF")
                + " &7(limit " + getConfig().getDouble("anti-freecam.max-block-interaction-distance", 6.0) + " blocks; alerts only)");
        say(s, "&7Player entity culling (anti-ESP/radar): "
                + (getConfig().getBoolean("entity-culling.enabled", true) ? "&aON" : "&cOFF")
                + " &7(" + getConfig().getInt("entity-culling.min-distance", 16) + "-"
                + getConfig().getInt("entity-culling.max-distance", 80) + " blocks)");
        say(s, "&7Combat log: &f" + getConfig().getString("combat.log-punishment", "kill") + " &7(tag "
                + getConfig().getLong("combat.tag-seconds", 15) + "s) | relog memory &f"
                + getConfig().getLong("relog.remember-minutes", 10) + " min");
        say(s, "&7Anti-xray (Paper chunk obfuscation):");
        boolean anyOff = false;
        for (Map.Entry<String, String> e : xrayWorlds.entrySet()) {
            boolean on = e.getValue().startsWith("ON");
            say(s, "&8  - &f" + e.getKey() + "&7: " + (on ? "&a" : "&c") + e.getValue());
            if (!on || !e.getValue().contains("admin bypass permission ON")) anyOff = true;
        }
        if (anyOff) {
            say(s, "&eFix: in config/paper-world-defaults.yml set enabled: true, engine-mode: 2, and use-permission: true, then restart. Give paper.antixray.bypass only to trusted staff.");
        }
    }

    // ---------------- messages ----------------

    private static void say(CommandSender s, String legacy) {
        s.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&8[&cAC&8] " + legacy));
    }

    private void notifyStaff(String legacy) {
        Component c = LegacyComponentSerializer.legacyAmpersand().deserialize("&8[&cAC&8] " + legacy);
        for (Player s : Bukkit.getOnlinePlayers()) {
            if (s.hasPermission("anticheat.alerts")) s.sendMessage(c);
        }
    }

    private static String ago(long ms) {
        long sec = ms / 1000;
        return sec < 60 ? sec + "s ago" : (sec / 60) + "m ago";
    }

    // ---------------- stationary hover check ----------------

    /** A perfectly still player sends no movement packets, so the move event never sees a hovering cheater. */
    private void hoverCheck() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Data d = data.get(p.getUniqueId());
            if (d == null) continue;
            if (frozen.contains(p.getUniqueId()) || exempt(p, d, now) || now - d.lastMoveMs < 400) {
                d.hoverSince = 0;
                continue;
            }
            int f = scan(p.getLocation());
            if ((f & (SUPPORT | SPECIAL)) != 0 || standingOnEntity(p)) {
                d.hoverSince = 0;
                continue;
            }
            if (d.hoverSince == 0) {
                d.hoverSince = now;
            } else if (now - d.hoverSince > 1500) {
                flag(p, d, "Fly", 1, "hovering still in mid-air", true);
            }
        }
    }

    /** Boats, minecarts and shulkers are legitimate things to stand on. */
    private static boolean standingOnEntity(Player p) {
        BoundingBox zone = p.getBoundingBox().expand(0.4, 0.8, 0.4);
        for (org.bukkit.entity.Entity e : p.getWorld().getNearbyEntities(zone)) {
            if (e != p && (e instanceof org.bukkit.entity.Vehicle || e instanceof org.bukkit.entity.Shulker)) return true;
        }
        return false;
    }

    // ---------------- helpers ----------------

    private static double wrap(double a) {
        a %= 360;
        if (a > 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
