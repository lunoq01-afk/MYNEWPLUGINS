package dev.core;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Projectile;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * Core: menu-based player commands (/settings /home /rtp /tpa /warp /msg /ignore ...),
 * the /offend punishment system, and /invsee.
 * Every command that needs a choice opens a menu. Pure toggles and one-step teleports
 * (/spawn, /fly, /nightvision, /togglechat) act instantly and also appear in /settings.
 */
public final class CorePlugin extends JavaPlugin implements Listener {

    private static final String PREFIX = "&8[&bCore&8] ";
    private static final String ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Set<String> CHAT_CMDS = Set.of("msg", "w", "whisper", "tell", "t", "pm", "r", "reply", "me", "say");
    private static final Set<Material> UNSAFE = Set.of(Material.CACTUS, Material.MAGMA_BLOCK, Material.FIRE,
            Material.SOUL_FIRE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW,
            Material.SWEET_BERRY_BUSH, Material.LAVA, Material.WATER, Material.WITHER_ROSE,
            Material.POINTED_DRIPSTONE, Material.COBWEB);

    // ---------------- data ----------------
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> off = new ConcurrentHashMap<>();        // settings switched OFF: chat, msg, tpa
    private final Map<UUID, Set<UUID>> ignored = new ConcurrentHashMap<>();
    private final Set<UUID> nv = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Map<Integer, Location>> homes = new HashMap<>();
    private final Map<String, Warp> warps = new LinkedHashMap<>();
    private final List<Punishment> punishments = new CopyOnWriteArrayList<>();
    private Location spawn;

    private final Map<UUID, Map<UUID, Request>> requests = new HashMap<>();   // target -> (sender -> request)
    private final Map<UUID, UUID> lastMsg = new HashMap<>();
    private final Map<UUID, UUID> pendingMsg = new ConcurrentHashMap<>();
    private final Map<UUID, Long> rtpCooldown = new HashMap<>();
    private final Map<UUID, Location> afkReturn = new HashMap<>();
    private final Map<UUID, Integer> afkMinutes = new HashMap<>();

    private boolean dirty;
    private File dataFile;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    private final Map<UUID, Consumer<String>> prompts = new ConcurrentHashMap<>();   // waiting for a chat reply
    private final Map<UUID, Map<Integer, String>> homeNames = new HashMap<>();
    private final Set<UUID> fastCrystals = ConcurrentHashMap.newKeySet();
    private final ExecutorService io = Executors.newSingleThreadExecutor();           // ordered ender chest writes

    private final Map<String, Team> teams = new LinkedHashMap<>();                    // lowercase name -> team
    private final Map<UUID, String> playerTeam = new ConcurrentHashMap<>();           // player -> lowercase team name
    private final Map<UUID, Map<String, Long>> invites = new HashMap<>();             // player -> (team -> expiry)
    private final Set<UUID> teamChat = ConcurrentHashMap.newKeySet();

    private static final class Team {
        final String name;
        UUID owner;
        final Set<UUID> members = new LinkedHashSet<>();
        Location home;
        boolean ff;
        Team(String name, UUID owner) { this.name = name; this.owner = owner; members.add(owner); }
    }

    private static final class EChestHolder implements InventoryHolder {
        final UUID owner;
        Inventory inv;
        EChestHolder(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inv; }
    }

    private record Warp(String name, Location loc, Material icon) { }

    private static final class Request {
        final UUID from; final boolean here; final long expires;
        Request(UUID from, boolean here, long expires) { this.from = from; this.here = here; this.expires = expires; }
    }

    private static final class Punishment {
        final String id; final UUID uuid; final String name, type, reason, staff;
        final long created, until;   // until == 0 means permanent
        volatile boolean active = true;
        Punishment(String id, UUID uuid, String name, String type, String reason, String staff, long created, long until) {
            this.id = id; this.uuid = uuid; this.name = name; this.type = type;
            this.reason = reason; this.staff = staff; this.created = created; this.until = until;
        }
        boolean live(long now) { return active && (until == 0 || now < until); }
    }

    private enum Mode { HOME, SET, DEL }

    /** Generic menu: each slot can have a click action. */
    private static final class Menu implements InventoryHolder {
        final Inventory inv;
        final Map<Integer, BiConsumer<Player, ClickType>> actions = new HashMap<>();
        Consumer<InventoryClickEvent> raw;   // full control over clicks (used by /invsee)
        Runnable refresh;                    // called every 5 ticks while open
        Menu(Component title, int rows) { inv = Bukkit.createInventory(this, rows * 9, title); }
        void set(int slot, ItemStack it, BiConsumer<Player, ClickType> action) {
            inv.setItem(slot, it);
            if (action != null) actions.put(slot, action);
        }
        void open(Player p) { p.openInventory(inv); }
        @Override public Inventory getInventory() { return inv; }
    }

    // ---------------- lifecycle ----------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        load();
        Bukkit.getPluginManager().registerEvents(this, this);
        for (String c : List.of("settings", "home", "sethome", "delhome", "spawn", "setspawn", "rtp", "tpa", "tpahere",
                "tpaccept", "tpdeny", "warp", "setwarp", "delwarp", "afk", "msg", "reply", "ignore", "togglechat",
                "nightvision", "fly", "rules", "discord", "offend", "unoffend", "offends", "invsee", "team", "tc", "enderchest", "media", "vanish")) {
            if (getCommand(c) != null) getCommand(c).setExecutor(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, () -> { if (dirty) save(); }, 100L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, this::refreshMenus, 5L, 5L);
        Bukkit.getScheduler().runTaskTimer(this, this::afkTick, 1200L, 1200L);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof EChestHolder h) saveEchest(h);
        }
        save();
        io.shutdown();
        try { io.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    // ---------------- persistence ----------------

    private void load() {
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection s;

        s = y.getConfigurationSection("names");
        if (s != null) for (String k : s.getKeys(false)) {
            try { names.put(UUID.fromString(k), s.getString(k, "?")); } catch (Exception ignoredEx) { }
        }
        for (String u : y.getStringList("night-vision")) {
            try { nv.add(UUID.fromString(u)); } catch (Exception ignoredEx) { }
        }
        s = y.getConfigurationSection("settings-off");
        if (s != null) for (String k : s.getKeys(false)) {
            try {
                Set<String> set = ConcurrentHashMap.newKeySet();
                set.addAll(s.getStringList(k));
                off.put(UUID.fromString(k), set);
            } catch (Exception ignoredEx) { }
        }
        s = y.getConfigurationSection("ignored");
        if (s != null) for (String k : s.getKeys(false)) {
            try {
                Set<UUID> set = ConcurrentHashMap.newKeySet();
                for (String u : s.getStringList(k)) set.add(UUID.fromString(u));
                ignored.put(UUID.fromString(k), set);
            } catch (Exception ignoredEx) { }
        }
        s = y.getConfigurationSection("homes");
        if (s != null) for (String k : s.getKeys(false)) {
            try {
                ConfigurationSection hs = s.getConfigurationSection(k);
                if (hs == null) continue;
                Map<Integer, Location> m = new HashMap<>();
                for (String n : hs.getKeys(false)) {
                    Location l = hs.getLocation(n);
                    if (l != null) m.put(Integer.parseInt(n), l);
                }
                homes.put(UUID.fromString(k), m);
            } catch (Exception ignoredEx) { }
        }
        spawn = y.getLocation("spawn");
        s = y.getConfigurationSection("warps");
        if (s != null) for (String k : s.getKeys(false)) {
            try {
                Location l = s.getLocation(k + ".loc");
                Material icon = Material.matchMaterial(s.getString(k + ".icon", "ENDER_PEARL"));
                if (l != null) warps.put(k, new Warp(s.getString(k + ".name", k), l, icon == null ? Material.ENDER_PEARL : icon));
            } catch (Exception ignoredEx) { }
        }
        for (String u : y.getStringList("fast-crystals")) {
            try { fastCrystals.add(UUID.fromString(u)); } catch (Exception ignoredEx) { }
        }
        for (String u : y.getStringList("vanished")) {
            try { vanished.add(UUID.fromString(u)); } catch (Exception ignoredEx) { }
        }
        s = y.getConfigurationSection("home-names");
        if (s != null) for (String k : s.getKeys(false)) {
            try {
                ConfigurationSection hs = s.getConfigurationSection(k);
                if (hs == null) continue;
                Map<Integer, String> hn = new HashMap<>();
                for (String n : hs.getKeys(false)) hn.put(Integer.parseInt(n), hs.getString(n, ""));
                homeNames.put(UUID.fromString(k), hn);
            } catch (Exception ignoredEx) { }
        }
        s = y.getConfigurationSection("teams");
        if (s != null) for (String k : s.getKeys(false)) {
            try {
                String b = k + ".";
                Team team = new Team(s.getString(b + "name", k), UUID.fromString(s.getString(b + "owner", "")));
                team.members.clear();
                for (String u : s.getStringList(b + "members")) team.members.add(UUID.fromString(u));
                if (team.members.isEmpty()) continue;
                if (!team.members.contains(team.owner)) team.owner = team.members.iterator().next();
                team.home = s.getLocation(b + "home");
                team.ff = s.getBoolean(b + "ff");
                teams.put(k, team);
                for (UUID u : team.members) playerTeam.put(u, k);
            } catch (Exception ignoredEx) { }
        }
        s = y.getConfigurationSection("punishments");
        if (s != null) for (String id : s.getKeys(false)) {
            try {
                String b = id + ".";
                Punishment p = new Punishment(id, UUID.fromString(s.getString(b + "uuid", "")), s.getString(b + "name", "?"),
                        s.getString(b + "type", "ban"), s.getString(b + "reason", "?"), s.getString(b + "staff", "?"),
                        s.getLong(b + "created"), s.getLong(b + "until"));
                p.active = s.getBoolean(b + "active", true);
                punishments.add(p);
            } catch (Exception ex) {
                getLogger().warning("Skipping bad punishment " + id);
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        names.forEach((k, v) -> y.set("names." + k, v));
        y.set("night-vision", nv.stream().map(UUID::toString).toList());
        off.forEach((k, v) -> y.set("settings-off." + k, new ArrayList<>(v)));
        ignored.forEach((k, v) -> y.set("ignored." + k, v.stream().map(UUID::toString).toList()));
        homes.forEach((k, m) -> m.forEach((n, l) -> y.set("homes." + k + "." + n, l)));
        if (spawn != null) y.set("spawn", spawn);
        for (Map.Entry<String, Warp> e : warps.entrySet()) {
            String b = "warps." + e.getKey();
            y.set(b + ".name", e.getValue().name());
            y.set(b + ".loc", e.getValue().loc());
            y.set(b + ".icon", e.getValue().icon().name());
        }
        for (Punishment p : punishments) {
            String b = "punishments." + p.id + ".";
            y.set(b + "uuid", p.uuid.toString());
            y.set(b + "name", p.name);
            y.set(b + "type", p.type);
            y.set(b + "reason", p.reason);
            y.set(b + "staff", p.staff);
            y.set(b + "created", p.created);
            y.set(b + "until", p.until);
            y.set(b + "active", p.active);
        }
        y.set("fast-crystals", fastCrystals.stream().map(UUID::toString).toList());
        y.set("vanished", vanished.stream().map(UUID::toString).toList());
        homeNames.forEach((k, hn) -> hn.forEach((n, nm) -> y.set("home-names." + k + "." + n, nm)));
        for (Team team : teams.values()) {
            String b = "teams." + team.name.toLowerCase(Locale.ROOT) + ".";
            y.set(b + "name", team.name);
            y.set(b + "owner", team.owner.toString());
            y.set(b + "members", team.members.stream().map(UUID::toString).toList());
            if (team.home != null) y.set(b + "home", team.home);
            y.set(b + "ff", team.ff);
        }
        try {
            y.save(dataFile);
            dirty = false;
        } catch (IOException ex) {
            getLogger().severe("Could not save data.yml: " + ex.getMessage());
        }
    }

    // ---------------- helpers ----------------

    private static Component line(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy).decoration(TextDecoration.ITALIC, false);
    }

    private static Component t(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }

    private static void msg(CommandSender s, String legacy) {
        s.sendMessage(t(PREFIX + legacy));
    }

    private static String pretty(Material m) { return m.name().toLowerCase(Locale.ROOT).replace('_', ' '); }

    private static ItemStack icon(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(line(name));
            if (lore != null) meta.lore(lore.stream().map(CorePlugin::line).toList());
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack head(OfflinePlayer op, String name, List<String> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        meta.setOwningPlayer(op);
        meta.displayName(line(name));
        if (lore != null) meta.lore(lore.stream().map(CorePlugin::line).toList());
        it.setItemMeta(meta);
        return it;
    }

    private UUID find(String name) {
        Player on = Bukkit.getPlayerExact(name);
        if (on != null) return on.getUniqueId();
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
        return op == null ? null : op.getUniqueId();
    }

    private String name(UUID u) { return names.getOrDefault(u, "Unknown"); }

    private boolean off(UUID u, String key) {
        Set<String> s = off.get(u);
        return s != null && s.contains(key);
    }

    /** Flips a setting; returns true if it is now OFF. */
    private boolean toggleOff(UUID u, String key) {
        Set<String> s = off.computeIfAbsent(u, x -> ConcurrentHashMap.newKeySet());
        boolean nowOff = s.add(key);
        if (!nowOff) s.remove(key);
        dirty = true;
        return nowOff;
    }

    private boolean ignores(UUID who, UUID target) {
        Set<UUID> s = ignored.get(who);
        return s != null && s.contains(target);
    }

    private static String fmtDuration(long ms) {
        long sec = ms / 1000;
        long d = sec / 86400, h = (sec % 86400) / 3600, m = (sec % 3600) / 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return Math.max(1, m) + "m";
    }

    /** Returns milliseconds, 0 for permanent, -1 if invalid. */
    private static long parseDuration(String s) {
        s = s.toLowerCase(Locale.ROOT).trim();
        if (s.equals("perm") || s.equals("permanent") || s.equals("0")) return 0;
        if (s.length() < 2) return -1;
        try {
            long n = Long.parseLong(s.substring(0, s.length() - 1));
            if (n <= 0) return -1;
            return switch (s.charAt(s.length() - 1)) {
                case 'm' -> n * 60_000L;
                case 'h' -> n * 3_600_000L;
                case 'd' -> n * 86_400_000L;
                case 'w' -> n * 7 * 86_400_000L;
                default -> -1;
            };
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static boolean isPlayer(CommandSender s) {
        if (s instanceof Player) return true;
        msg(s, "&cPlayers only.");
        return false;
    }

    // ---------------- commands ----------------

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        switch (c.getName().toLowerCase(Locale.ROOT)) {
            case "settings" -> { if (isPlayer(s)) openSettings((Player) s); }
            case "home" -> cmdHome(s, a, Mode.HOME);
            case "sethome" -> cmdHome(s, a, Mode.SET);
            case "delhome" -> cmdHome(s, a, Mode.DEL);
            case "spawn" -> cmdSpawn(s);
            case "setspawn" -> {
                if (isPlayer(s)) {
                    spawn = ((Player) s).getLocation();
                    dirty = true;
                    msg(s, "&aSpawn set to your location.");
                }
            }
            case "rtp" -> { if (isPlayer(s)) openRtp((Player) s); }
            case "tpa" -> cmdTpa(s, a, false);
            case "tpahere" -> cmdTpa(s, a, true);
            case "tpaccept" -> cmdTpaccept(s, a, true);
            case "tpdeny" -> cmdTpaccept(s, a, false);
            case "warp" -> cmdWarp(s, a);
            case "setwarp" -> cmdSetWarp(s, a);
            case "delwarp" -> cmdDelWarp(s, a);
            case "afk" -> cmdAfk(s);
            case "msg" -> cmdMsg(s, a);
            case "reply" -> cmdReply(s, a);
            case "ignore" -> cmdIgnore(s, a);
            case "togglechat" -> {
                if (isPlayer(s)) {
                    boolean nowOff = toggleOff(((Player) s).getUniqueId(), "chat");
                    msg(s, nowOff ? "&7Public chat: &cOFF" : "&7Public chat: &aON");
                }
            }
            case "nightvision" -> {
                if (isPlayer(s)) {
                    boolean on = toggleNv((Player) s);
                    msg(s, on ? "&7Night vision: &aON" : "&7Night vision: &cOFF");
                }
            }
            case "fly" -> cmdFly(s);
            case "rules" -> cmdRules(s);
            case "discord" -> cmdDiscord(s);
            case "offend" -> cmdOffend(s, a);
            case "unoffend" -> cmdUnoffend(s, a);
            case "offends" -> { if (isPlayer(s)) openOffends((Player) s, 0); }
            case "invsee" -> cmdInvsee(s, a);
            case "team" -> cmdTeam(s, a);
            case "tc" -> cmdTc(s, a);
            case "enderchest" -> { if (isPlayer(s)) openEchest((Player) s); }
            case "media" -> { if (isPlayer(s)) openMedia((Player) s); }
            case "vanish" -> cmdVanish(s);
            default -> { }
        }
        return true;
    }

    // ---------------- menu plumbing ----------------

    private void nav(Menu m, int page, int pages, IntConsumer go) {
        if (page > 0) m.set(45, icon(Material.ARROW, "&ePrevious page", null), (p, c) -> go.accept(page - 1));
        m.set(49, icon(Material.PAPER, "&7Page " + (page + 1) + "/" + pages, null), null);
        if (page < pages - 1) m.set(53, icon(Material.ARROW, "&eNext page", null), (p, c) -> go.accept(page + 1));
    }

    private void openPicker(Player viewer, String title, int page, Predicate<Player> filter, Consumer<Player> pick) {
        List<Player> list = new ArrayList<>(Bukkit.getOnlinePlayers());
        list.removeIf(x -> x.equals(viewer) || !filter.test(x)
                || (vanished.contains(x.getUniqueId()) && !canSeeVanished(viewer)));
        list.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        int pages = Math.max(1, (list.size() + 44) / 45);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Menu m = new Menu(t(title + " &8(" + (pg + 1) + "/" + pages + ")"), 6);
        for (int i = 0; i < 45; i++) {
            int idx = pg * 45 + i;
            if (idx >= list.size()) break;
            Player target = list.get(idx);
            m.set(i, head(target, "&f" + target.getName(), List.of("&eClick to select")), (pl, c) -> {
                Player now = Bukkit.getPlayer(target.getUniqueId());
                if (now == null) { msg(pl, "&cThat player went offline."); return; }
                pick.accept(now);
            });
        }
        nav(m, pg, pages, np -> openPicker(viewer, title, np, filter, pick));
        m.open(viewer);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        if (m.raw != null) { m.raw.accept(e); return; }
        BiConsumer<Player, ClickType> action = m.actions.get(e.getSlot());
        if (action == null) return;
        ClickType click = e.getClick();
        Bukkit.getScheduler().runTask(this, () -> action.accept(p, click));
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof Menu) e.setCancelled(true);
    }

    private void refreshMenus() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu m && m.refresh != null) {
                m.refresh.run();
            }
        }
    }

    // ---------------- /settings ----------------

    private void openSettings(Player p) {
        UUID id = p.getUniqueId();
        Menu m = new Menu(t("&bSettings"), 3);
        toggleItem(m, 10, "Public Chat", !off(id, "chat"), () -> toggleOff(id, "chat"));
        toggleItem(m, 11, "Private Messages", !off(id, "msg"), () -> toggleOff(id, "msg"));
        toggleItem(m, 12, "Teleport Requests", !off(id, "tpa"), () -> toggleOff(id, "tpa"));
        toggleItem(m, 13, "Night Vision", nv.contains(id), () -> toggleNv(p));
        m.set(14, icon(Material.ENDER_EYE, "&fScoreboard", List.of("&7Click to show or hide the sidebar")),
                (pl, c) -> { pl.performCommand("sb"); openSettings(pl); });
        toggleItem(m, 15, "Mob Spawning", !off(id, "mobs"), () -> toggleOff(id, "mobs"));
        toggleItem(m, 16, "Fast Crystals", fastCrystals.contains(id), () -> toggleFastCrystals(id));
        m.open(p);
    }

    private void toggleItem(Menu m, int slot, String label, boolean enabled, Runnable click) {
        m.set(slot, icon(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                        "&f" + label + ": " + (enabled ? "&aON" : "&cOFF"), List.of("&7Click to toggle")),
                (pl, c) -> { click.run(); openSettings(pl); });
    }

    private boolean toggleNv(Player p) {
        dirty = true;
        if (nv.remove(p.getUniqueId())) {
            p.removePotionEffect(PotionEffectType.NIGHT_VISION);
            return false;
        }
        nv.add(p.getUniqueId());
        applyNv(p);
        return true;
    }

    private void applyNv(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false, false));
    }

    // ---------------- homes ----------------

    private int maxHomes(Player p) {
        int max = getConfig().getInt("homes.default", 3);
        for (int i = 50; i > max; i--) {
            if (p.hasPermission("core.homes." + i)) { max = i; break; }
        }
        return Math.min(max, 54);
    }

    private void cmdHome(CommandSender s, String[] a, Mode mode) {
        if (!isPlayer(s)) return;
        Player p = (Player) s;
        if (a.length == 0) { openHomes(p, mode); return; }
        int n;
        try { n = Integer.parseInt(a[0]); } catch (NumberFormatException ex) { msg(p, "&cUse a home number, or just the command for the menu."); return; }
        homeAction(p, mode, n);
    }

    private void homeAction(Player p, Mode mode, int n) {
        if (n < 1 || n > maxHomes(p)) { msg(p, "&cYou can use homes 1-" + maxHomes(p) + "."); return; }
        Map<Integer, Location> h = homes.computeIfAbsent(p.getUniqueId(), x -> new HashMap<>());
        switch (mode) {
            case HOME -> {
                Location l = h.get(n);
                if (l == null || l.getWorld() == null) msg(p, "&cHome " + n + " isn't set.");
                else teleportWarmup(p, l, "home " + n);
            }
            case SET -> {
                h.put(n, p.getLocation());
                dirty = true;
                msg(p, "&aHome " + n + " set.");
            }
            case DEL -> {
                if (h.remove(n) != null) {
                    dirty = true;
                    Map<Integer, String> hn = homeNames.get(p.getUniqueId());
                    if (hn != null) hn.remove(n);
                    msg(p, "&aHome " + n + " deleted.");
                }
                else msg(p, "&7Home " + n + " isn't set.");
            }
        }
    }

    private String homeName(UUID u, int n) {
        Map<Integer, String> m = homeNames.get(u);
        String custom = m == null ? null : m.get(n);
        return custom != null && !custom.isBlank() ? custom : "Home " + n;
    }

    private void openHomes(Player p, Mode mode) {
        int max = maxHomes(p);
        int rows = Math.min(6, Math.max(1, (max + 8) / 9));
        Menu m = new Menu(t(mode == Mode.HOME ? "&bYour homes" : mode == Mode.SET ? "&aSet a home" : "&cDelete a home"), rows);
        Map<Integer, Location> h = homes.getOrDefault(p.getUniqueId(), Map.of());
        for (int i = 1; i <= max; i++) {
            final int n = i;
            Location l = h.get(i);
            ItemStack it;
            if (l != null && l.getWorld() != null) {
                List<String> lore = new ArrayList<>(List.of(
                        "&7World: &f" + l.getWorld().getName(),
                        "&7XYZ: &f" + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()));
                lore.add("");
                if (mode == Mode.HOME) {
                    lore.add("&aLeft-click: teleport");
                    lore.add("&eRight-click: rename");
                    lore.add("&cShift-click: delete");
                } else if (mode == Mode.SET) {
                    lore.add("&eClick to overwrite with here");
                } else {
                    lore.add("&cClick to delete");
                }
                it = icon(Material.RED_BED, "&b" + homeName(p.getUniqueId(), i), lore);
            } else {
                it = icon(mode == Mode.SET ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                        mode == Mode.SET ? "&aSet home " + i + " here" : "&7Home " + i + " (empty)", null);
            }
            m.set(i - 1, it, (pl, c) -> {
                if (mode == Mode.HOME) {
                    if (c == ClickType.SHIFT_LEFT || c == ClickType.SHIFT_RIGHT) confirmDeleteHome(pl, n);
                    else if (c == ClickType.RIGHT) renameHome(pl, n);
                    else { homeAction(pl, Mode.HOME, n); pl.closeInventory(); }
                } else {
                    homeAction(pl, mode, n);
                    openHomes(pl, mode);
                }
            });
        }
        m.open(p);
    }

    private void renameHome(Player p, int n) {
        if (homes.getOrDefault(p.getUniqueId(), Map.of()).get(n) == null) { msg(p, "&cSet home " + n + " first."); return; }
        prompt(p, "&7Type a new name for &f" + homeName(p.getUniqueId(), n) + " &7in chat (or type &fcancel&7).", text -> {
            String clean = text.replaceAll("[^A-Za-z0-9 _\\-]", "").trim();
            if (clean.isEmpty() || clean.length() > 24) { msg(p, "&cUse 1-24 letters, numbers or spaces."); return; }
            homeNames.computeIfAbsent(p.getUniqueId(), x -> new HashMap<>()).put(n, clean);
            dirty = true;
            msg(p, "&aHome " + n + " renamed to &f" + clean + "&a.");
            openHomes(p, Mode.HOME);
        });
    }

    private void confirmDeleteHome(Player p, int n) {
        Menu m = new Menu(t("&cDelete " + homeName(p.getUniqueId(), n) + "?"), 3);
        m.set(11, icon(Material.LIME_CONCRETE, "&aYes, delete it", null),
                (pl, c) -> { homeAction(pl, Mode.DEL, n); openHomes(pl, Mode.HOME); });
        m.set(15, icon(Material.RED_CONCRETE, "&cCancel", null), (pl, c) -> openHomes(pl, Mode.HOME));
        m.open(p);
    }

    // ---------------- teleport helpers ----------------

    private void teleportWarmup(Player who, Location dest, String what) {
        int sec = getConfig().getInt("tpa.warmup-seconds", 3);
        if (sec <= 0 || who.hasPermission("core.bypass.warmup")) { who.teleport(dest); return; }
        Location start = who.getLocation().clone();
        msg(who, "&7Teleporting to &f" + what + " &7in &f" + sec + "s&7, don't move!");
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!who.isOnline()) return;
            Location now = who.getLocation();
            if (now.getWorld() != start.getWorld() || now.distanceSquared(start) > 2.0) {
                msg(who, "&cTeleport cancelled, you moved.");
                return;
            }
            who.teleport(dest);
        }, sec * 20L);
    }

    private void cmdSpawn(CommandSender s) {
        if (!isPlayer(s)) return;
        Player p = (Player) s;
        p.teleport(spawn != null && spawn.getWorld() != null ? spawn : p.getWorld().getSpawnLocation());
    }

    // ---------------- /tpa family ----------------

    private void cmdTpa(CommandSender s, String[] a, boolean here) {
        if (!isPlayer(s)) return;
        Player p = (Player) s;
        if (a.length == 0) {
            openPicker(p, here ? "&bBring a player to you" : "&bTeleport to a player",
                    0, x -> true, target -> { p.closeInventory(); sendRequest(p, target, here); });
            return;
        }
        Player target = visibleOnline(p, a[0]);
        if (target == null) { msg(p, "&c" + a[0] + " is not online."); return; }
        sendRequest(p, target, here);
    }

    private void sendRequest(Player p, Player target, boolean here) {
        if (target.equals(p)) { msg(p, "&cYou can't teleport to yourself."); return; }
        boolean blocked = !p.hasPermission("core.bypass")
                && (off(target.getUniqueId(), "tpa") || ignores(target.getUniqueId(), p.getUniqueId()));
        if (blocked) { msg(p, "&c" + target.getName() + " isn't accepting teleport requests."); return; }

        long expire = getConfig().getLong("tpa.expire-seconds", 60) * 1000L;
        requests.computeIfAbsent(target.getUniqueId(), x -> new HashMap<>())
                .put(p.getUniqueId(), new Request(p.getUniqueId(), here, System.currentTimeMillis() + expire));
        msg(p, "&aTeleport request sent to &f" + target.getName() + "&a.");
        target.sendMessage(t(PREFIX + "&f" + p.getName() + " &7wants " + (here ? "you to teleport to them. " : "to teleport to you. "))
                .append(Component.text("[ACCEPT]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/tpaccept " + p.getName())))
                .append(Component.text(" "))
                .append(Component.text("[DENY]", NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/tpdeny " + p.getName()))));
    }

    private void cmdTpaccept(CommandSender s, String[] a, boolean accept) {
        if (!isPlayer(s)) return;
        Player p = (Player) s;
        if (a.length > 0) {
            Player from = Bukkit.getPlayerExact(a[0]);
            if (from == null) { msg(p, "&c" + a[0] + " is not online."); return; }
            respond(p, from.getUniqueId(), accept);
            return;
        }
        openRequests(p);
    }

    private void respond(Player p, UUID from, boolean accept) {
        Map<UUID, Request> m = requests.get(p.getUniqueId());
        Request r = m == null ? null : m.remove(from);
        if (r == null || System.currentTimeMillis() > r.expires) { msg(p, "&cNo active request from that player."); return; }
        Player f = Bukkit.getPlayer(from);
        if (f == null) { msg(p, "&cThat player went offline."); return; }
        if (!accept) {
            msg(p, "&7Request denied.");
            msg(f, "&c" + p.getName() + " denied your request.");
            return;
        }
        msg(p, "&aRequest accepted.");
        msg(f, "&a" + p.getName() + " accepted your request.");
        if (r.here) teleportWarmup(p, f.getLocation(), f.getName());
        else teleportWarmup(f, p.getLocation(), p.getName());
    }

    private void openRequests(Player p) {
        long now = System.currentTimeMillis();
        List<Request> live = new ArrayList<>();
        for (Request r : requests.getOrDefault(p.getUniqueId(), Map.of()).values()) {
            if (now <= r.expires) live.add(r);
        }
        if (live.isEmpty()) { msg(p, "&7You have no teleport requests."); return; }
        Menu m = new Menu(t("&bTeleport requests"), Math.min(6, (live.size() + 8) / 9));
        int i = 0;
        for (Request r : live) {
            if (i >= 54) break;
            m.set(i++, head(Bukkit.getOfflinePlayer(r.from), "&f" + name(r.from), List.of(
                            r.here ? "&7Wants you to teleport to them" : "&7Wants to teleport to you",
                            "&aLeft-click: accept", "&cRight-click: deny")),
                    (pl, c) -> {
                        respond(pl, r.from, c != ClickType.RIGHT && c != ClickType.SHIFT_RIGHT);
                        pl.closeInventory();
                    });
        }
        m.open(p);
    }

    // ---------------- /rtp ----------------

    private void openRtp(Player p) {
        Menu m = new Menu(t("&bRandom teleport"), 3);
        m.set(11, icon(Material.GRASS_BLOCK, "&aOverworld", List.of("&eClick to teleport")), (pl, c) -> { pl.closeInventory(); rtp(pl, World.Environment.NORMAL); });
        m.set(13, icon(Material.NETHERRACK, "&cNether", List.of("&eClick to teleport")), (pl, c) -> { pl.closeInventory(); rtp(pl, World.Environment.NETHER); });
        m.set(15, icon(Material.END_STONE, "&dThe End", List.of("&eClick to teleport")), (pl, c) -> { pl.closeInventory(); rtp(pl, World.Environment.THE_END); });
        m.open(p);
    }

    private void rtp(Player p, World.Environment env) {
        World w = null;
        for (World x : Bukkit.getWorlds()) {
            if (x.getEnvironment() == env) { w = x; break; }
        }
        if (w == null) { msg(p, "&cThat world isn't available."); return; }
        long now = System.currentTimeMillis();
        long cd = getConfig().getLong("rtp.cooldown-seconds", 30) * 1000L;
        Long last = rtpCooldown.get(p.getUniqueId());
        if (last != null && now - last < cd && !p.hasPermission("core.bypass.cooldown")) {
            msg(p, "&cWait " + ((cd - (now - last)) / 1000 + 1) + "s before using /rtp again.");
            return;
        }
        rtpCooldown.put(p.getUniqueId(), now);
        msg(p, "&7Searching for a safe spot...");
        rtpTry(p, w, env, 0);
    }

    private void rtpTry(Player p, World w, World.Environment env, int attempt) {
        if (!p.isOnline()) return;
        if (attempt >= 15) {
            msg(p, "&cCouldn't find a safe spot. Try again.");
            rtpCooldown.remove(p.getUniqueId());
            return;
        }
        String key = env == World.Environment.NETHER ? "nether" : env == World.Environment.THE_END ? "end" : "overworld";
        int min = getConfig().getInt("rtp." + key + ".min", 300);
        int max = getConfig().getInt("rtp." + key + ".max", 5000);
        double half = w.getWorldBorder().getSize() / 2.0 - 16;
        max = (int) Math.max(1, Math.min(max, half));
        min = Math.min(min, Math.max(0, max - 1));
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double dist = min + r.nextDouble() * Math.max(1, max - min);
        double ang = r.nextDouble() * Math.PI * 2;
        Location c = w.getWorldBorder().getCenter();
        int x = (int) Math.floor(c.getX() + Math.cos(ang) * dist);
        int z = (int) Math.floor(c.getZ() + Math.sin(ang) * dist);
        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> Bukkit.getScheduler().runTask(this, () -> {
            Location spot = safeSpot(w, env, x, z);
            if (spot == null) { rtpTry(p, w, env, attempt + 1); return; }
            p.teleport(spot);
            msg(p, "&aTeleported to &f" + spot.getBlockX() + " " + spot.getBlockY() + " " + spot.getBlockZ() + "&a.");
        }));
    }

    private Location safeSpot(World w, World.Environment env, int x, int z) {
        if (env == World.Environment.NETHER) {
            for (int y = 120; y >= 33; y--) {
                Block b = w.getBlockAt(x, y, z);
                Block a1 = w.getBlockAt(x, y + 1, z);
                Block a2 = w.getBlockAt(x, y + 2, z);
                if (b.getType().isSolid() && !UNSAFE.contains(b.getType())
                        && a1.isPassable() && a2.isPassable() && !a1.isLiquid() && !a2.isLiquid()) {
                    return new Location(w, x + 0.5, y + 1, z + 0.5);
                }
            }
            return null;
        }
        int y = w.getHighestBlockYAt(x, z);
        if (y <= w.getMinHeight()) return null;
        Block b = w.getBlockAt(x, y, z);
        if (!b.getType().isSolid() || UNSAFE.contains(b.getType())) return null;
        if (!w.getBlockAt(x, y + 1, z).isPassable() || !w.getBlockAt(x, y + 2, z).isPassable()) return null;
        return new Location(w, x + 0.5, y + 1, z + 0.5);
    }

    // ---------------- warps, afk ----------------

    private void cmdWarp(CommandSender s, String[] a) {
        if (!isPlayer(s)) return;
        Player p = (Player) s;
        if (a.length == 0) { openWarps(p); return; }
        Warp w = warps.get(a[0].toLowerCase(Locale.ROOT));
        if (w == null) { msg(p, "&cUnknown warp."); return; }
        teleportWarmup(p, w.loc(), w.name());
    }

    private void openWarps(Player p) {
        if (warps.isEmpty()) { msg(p, "&7No warps have been set up yet."); return; }
        Menu m = new Menu(t("&bWarps"), Math.min(6, (warps.size() + 8) / 9));
        int i = 0;
        for (Warp w : warps.values()) {
            if (i >= 54) break;
            m.set(i++, icon(w.icon(), "&b" + w.name(), List.of("&eClick to teleport")),
                    (pl, c) -> { pl.closeInventory(); teleportWarmup(pl, w.loc(), w.name()); });
        }
        m.open(p);
    }

    private void cmdSetWarp(CommandSender s, String[] a) {
        if (!isPlayer(s)) return;
        Player p = (Player) s;
        if (a.length < 1 || !a[0].matches("[A-Za-z0-9_\\-]{1,24}")) {
            msg(p, "&cUsage: /setwarp <name> (letters, numbers, - and _). The item in your hand becomes the icon.");
            return;
        }
        Material icon = p.getInventory().getItemInMainHand().getType();
        if (icon.isAir()) icon = Material.ENDER_PEARL;
        warps.put(a[0].toLowerCase(Locale.ROOT), new Warp(a[0], p.getLocation(), icon));
        dirty = true;
        msg(p, "&aWarp &f" + a[0] + " &aset.");
    }

    private void cmdDelWarp(CommandSender s, String[] a) {
        if (a.length < 1) { msg(s, "&cUsage: /delwarp <name>"); return; }
        if (warps.remove(a[0].toLowerCase(Locale.ROOT)) != null) { dirty = true; msg(s, "&aWarp deleted."); }
        else msg(s, "&cUnknown warp.");
    }

    private boolean inAfk(Player p, Warp w) {
        return w.loc().getWorld() != null && p.getWorld().equals(w.loc().getWorld())
                && p.getLocation().distanceSquared(w.loc()) < 2500;
    }

    private void cmdAfk(CommandSender s) {
        if (!isPlayer(s)) return;
        Player p = (Player) s;
        Warp w = warps.get("afk");
        if (w == null) { msg(p, "&cNo AFK area is set up. Admins: stand there and run /setwarp afk"); return; }
        Location back = afkReturn.remove(p.getUniqueId());
        if (back != null && inAfk(p, w)) { p.teleport(back); msg(p, "&aWelcome back."); return; }
        afkReturn.put(p.getUniqueId(), p.getLocation());
        p.teleport(w.loc());
        msg(p, "&7You're in the AFK area. Run &f/afk &7again to go back.");
    }

    private void afkTick() {
        Warp w = warps.get("afk");
        if (w == null) return;
        int need = getConfig().getInt("afk.minutes", 5);
        String cmd = getConfig().getString("afk.reward-command", "");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!inAfk(p, w)) { afkMinutes.remove(p.getUniqueId()); continue; }
            int n = afkMinutes.merge(p.getUniqueId(), 1, Integer::sum);
            if (n >= need) {
                afkMinutes.put(p.getUniqueId(), 0);
                if (cmd != null && !cmd.isBlank()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
                    msg(p, "&aYou earned a reward for being AFK!");
                }
            }
        }
    }

    // ---------------- messaging / ignore / misc toggles ----------------

    private void cmdMsg(CommandSender s, String[] a) {
        if (!isPlayer(s)) return;
        Player p = (Player) s;
        if (a.length == 0) {
            openPicker(p, "&bPrivate message", 0, x -> true, target -> {
                pendingMsg.put(p.getUniqueId(), target.getUniqueId());
                p.closeInventory();
                msg(p, "&7Type your message to &f" + target.getName() + " &7in chat (or type &fcancel&7).");
            });
            return;
        }
        if (a.length < 2) { msg(p, "&cUsage: /msg <player> <message>"); return; }
        Player target = visibleOnline(p, a[0]);
        if (target == null) { msg(p, "&c" + a[0] + " is not online."); return; }
        sendPm(p, target, String.join(" ", Arrays.copyOfRange(a, 1, a.length)));
    }

    private void cmdReply(CommandSender s, String[] a) {
        if (!isPlayer(s)) return;
        Player p = (Player) s;
        if (a.length < 1) { msg(p, "&cUsage: /r <message>"); return; }
        UUID last = lastMsg.get(p.getUniqueId());
        Player target = last == null ? null : Bukkit.getPlayer(last);
        if (target == null) { msg(p, "&cNobody to reply to."); return; }
        sendPm(p, target, String.join(" ", a));
    }

    private void sendPm(Player from, Player to, String text) {
        Punishment mute = activeMute(from.getUniqueId());
        if (mute != null) { notifyMuted(from, mute); return; }
        if (!from.hasPermission("core.bypass")
                && (off(to.getUniqueId(), "msg") || ignores(to.getUniqueId(), from.getUniqueId()))) {
            msg(from, "&c" + to.getName() + " isn't accepting private messages.");
            return;
        }
        if (ignores(from.getUniqueId(), to.getUniqueId())) {
            msg(from, "&cYou are ignoring that player. Use /ignore to stop.");
            return;
        }
        from.sendMessage(Component.text("[You -> " + to.getName() + "] ", NamedTextColor.GRAY).append(Component.text(text, NamedTextColor.WHITE)));
        to.sendMessage(Component.text("[" + from.getName() + " -> You] ", NamedTextColor.GRAY).append(Component.text(text, NamedTextColor.WHITE)));
        lastMsg.put(from.getUniqueId(), to.getUniqueId());
        lastMsg.put(to.getUniqueId(), from.getUniqueId());
    }

    private void cmdIgnore(CommandSender s, String[] a) {
        if (!isPlayer(s)) return;
        Player p = (Player) s;
        if (a.length == 0) {
            openPicker(p, "&bIgnore / unignore", 0, x -> true, target -> { p.closeInventory(); toggleIgnore(p, target.getUniqueId()); });
            return;
        }
        UUID u = find(a[0]);
        if (u == null) { msg(p, "&cUnknown player."); return; }
        toggleIgnore(p, u);
    }

    private void toggleIgnore(Player p, UUID target) {
        if (target.equals(p.getUniqueId())) { msg(p, "&cYou can't ignore yourself."); return; }
        Set<UUID> set = ignored.computeIfAbsent(p.getUniqueId(), x -> ConcurrentHashMap.newKeySet());
        boolean nowIgnoring = set.add(target);
        if (!nowIgnoring) set.remove(target);
        dirty = true;
        msg(p, nowIgnoring
                ? "&7Now ignoring &f" + name(target) + " &7(messages, teleport requests and chat)."
                : "&aNo longer ignoring &f" + name(target) + "&a.");
    }

    private void cmdFly(CommandSender s) {
        if (!isPlayer(s)) return;
        Player p = (Player) s;
        boolean on = !p.getAllowFlight();
        p.setAllowFlight(on);
        if (!on) p.setFlying(false);
        msg(p, on ? "&7Flight: &aON" : "&7Flight: &cOFF");
    }

    private void cmdRules(CommandSender s) {
        if (!isPlayer(s)) return;
        List<String> pages = getConfig().getStringList("rules");
        if (pages.isEmpty()) { msg(s, "&7No rules have been set up."); return; }
        List<Component> comps = pages.stream().map(CorePlugin::t).toList();
        ((Player) s).openBook(Book.book(Component.text("Rules"), Component.text("Server"), comps));
    }

    private void cmdDiscord(CommandSender s) {
        String link = getConfig().getString("discord", "");
        s.sendMessage(t(PREFIX + "&7Join our Discord: ").append(Component.text(link, NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(link))));
    }

    // ---------------- chat events ----------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        UUID target = pendingMsg.remove(id);
        if (target != null) {
            e.setCancelled(true);
            String text = PlainTextComponentSerializer.plainText().serialize(e.message());
            Bukkit.getScheduler().runTask(this, () -> {
                if (text.equalsIgnoreCase("cancel")) { msg(p, "&7Cancelled."); return; }
                Player to = Bukkit.getPlayer(target);
                if (to == null) { msg(p, "&cThat player went offline."); return; }
                sendPm(p, to, text);
            });
            return;
        }

        Consumer<String> prompt = prompts.remove(id);
        if (prompt != null) {
            e.setCancelled(true);
            String answer = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
            Bukkit.getScheduler().runTask(this, () -> {
                if (answer.equalsIgnoreCase("cancel")) msg(p, "&7Cancelled.");
                else prompt.accept(answer);
            });
            return;
        }

        Punishment mute = activeMute(id);
        if (mute != null) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(this, () -> notifyMuted(p, mute));
            return;
        }
        if (teamChat.contains(id) && playerTeam.containsKey(id)) {
            e.setCancelled(true);
            String teamText = PlainTextComponentSerializer.plainText().serialize(e.message());
            Bukkit.getScheduler().runTask(this, () -> sendTeamChat(p, teamText));
            return;
        }
        e.viewers().removeIf(a -> a instanceof Player v && !v.equals(p)
                && (off(v.getUniqueId(), "chat") || ignores(v.getUniqueId(), id)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommandPre(PlayerCommandPreprocessEvent e) {
        String raw = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        int colon = raw.indexOf(':');
        if (colon >= 0) raw = raw.substring(colon + 1);
        if (!CHAT_CMDS.contains(raw)) return;
        Punishment m = activeMute(e.getPlayer().getUniqueId());
        if (m != null) {
            e.setCancelled(true);
            notifyMuted(e.getPlayer(), m);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        names.put(p.getUniqueId(), p.getName());
        dirty = true;
        if (nv.contains(p.getUniqueId())) applyNv(p);
        // vanish: hide vanished staff from the joining player; a staff member who left vanished rejoins vanished
        if (!canSeeVanished(p)) {
            for (UUID v : vanished) {
                Player vp = Bukkit.getPlayer(v);
                if (vp != null) p.hidePlayer(this, vp);
            }
        }
        if (vanished.contains(p.getUniqueId())) {
            e.joinMessage(null);
            applyVanish(p);
            Bukkit.getScheduler().runTaskLater(this, () -> msg(p, "&7You joined vanished. Use /vanish to become visible."), 20L);
        }
        if (!p.hasPlayedBefore()) {   // brand new player: send them to spawn
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) p.teleport(spawn != null && spawn.getWorld() != null ? spawn : p.getWorld().getSpawnLocation());
            }, 5L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        pendingMsg.remove(id);
        requests.remove(id);
        prompts.remove(id);
        if (vanished.contains(id)) e.quitMessage(null);   // vanished staff leave silently
        if (e.getPlayer().getOpenInventory().getTopInventory().getHolder(false) instanceof EChestHolder h) saveEchest(h);
    }

    // ---------------- /offend punishments ----------------

    private Punishment activeMute(UUID u) {
        long now = System.currentTimeMillis();
        for (Punishment p : punishments) {
            if (p.type.equals("mute") && p.uuid.equals(u) && p.live(now)) return p;
        }
        return null;
    }

    private void notifyMuted(Player p, Punishment m) {
        String left = m.until == 0 ? "permanent" : fmtDuration(m.until - System.currentTimeMillis());
        msg(p, "&cYou have been muted for &f" + m.reason + " &c(" + left + "). You can't talk, chat or message!");
    }

    private Component kickScreen(Punishment p) {
        List<String> lines = getConfig().getStringList("offend.kick-screen");
        if (lines.isEmpty()) lines = List.of("&c&lYOU ARE BANNED", "&7Reason: &f%reason%", "&7Ban ID: &f#%id%");
        String time = p.until == 0 ? "Never (permanent)" : fmtDuration(Math.max(0, p.until - System.currentTimeMillis()));
        List<Component> parts = new ArrayList<>();
        for (String l : lines) {
            parts.add(t(l.replace("%reason%", p.reason).replace("%id%", p.id).replace("%time%", time)));
        }
        return Component.join(JoinConfiguration.newlines(), parts);
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        long now = System.currentTimeMillis();
        for (Punishment p : punishments) {
            if (p.type.equals("ban") && p.uuid.equals(e.getUniqueId()) && p.live(now)) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, kickScreen(p));
                return;
            }
        }
    }

    private String newId() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(ID_CHARS.charAt(r.nextInt(ID_CHARS.length())));
        return sb.toString();
    }

    private void cmdOffend(CommandSender s, String[] a) {
        ConfigurationSection reasons = getConfig().getConfigurationSection("offend.reasons");
        if (a.length < 2) {
            msg(s, "&cUsage: /offend <player> <reason> [duration]");
            msg(s, "&7Reasons: &f" + (reasons == null ? "none" : String.join(", ", reasons.getKeys(false))));
            return;
        }
        UUID u = find(a[0]);
        if (u == null) { msg(s, "&cUnknown player (they must have joined once)."); return; }
        ConfigurationSection r = null;
        if (reasons != null) {
            for (String k : reasons.getKeys(false)) {
                if (k.equalsIgnoreCase(a[1])) { r = reasons.getConfigurationSection(k); break; }
            }
        }
        if (r == null) { msg(s, "&cUnknown reason. Reasons: &f" + (reasons == null ? "none" : String.join(", ", reasons.getKeys(false)))); return; }

        Player online = Bukkit.getPlayer(u);
        if (online != null && online.hasPermission("core.bypass")) { msg(s, "&cThat player can't be punished."); return; }

        String type = r.getString("type", "ban").toLowerCase(Locale.ROOT);
        if (!type.equals("ban") && !type.equals("mute") && !type.equals("kick")) { msg(s, "&cBad type in config for that reason."); return; }
        String durText = a.length > 2 ? a[2] : String.valueOf(r.get("duration", "perm"));
        long dur = parseDuration(durText);
        if (dur < 0) { msg(s, "&cInvalid duration. Use 30m, 12h, 15d or perm."); return; }

        long now = System.currentTimeMillis();
        Punishment p = new Punishment(newId(), u, name(u), type, r.getString("message", a[1]), s.getName(),
                now, dur == 0 ? 0 : now + dur);
        if (type.equals("kick")) p.active = false;
        punishments.add(p);

        if (online != null) {
            if (type.equals("ban") || type.equals("kick")) online.kick(kickScreen(p));
            else notifyMuted(online, p);
        }
        String when = type.equals("kick") ? "kick" : (dur == 0 ? "permanent" : fmtDuration(dur));
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("core.offend")) {
                msg(staff, "&c" + s.getName() + " &7offended &f" + p.name + " &7(" + type + ", " + when + ") for &f" + p.reason + " &8#" + p.id);
            }
        }
        getLogger().info(s.getName() + " offended " + p.name + " (" + type + ", " + when + ") for " + p.reason + " #" + p.id);
        save();
    }

    private void cmdUnoffend(CommandSender s, String[] a) {
        if (a.length < 1) { msg(s, "&cUsage: /unoffend <player|banId>"); return; }
        String q = a[0].replace("#", "");
        long now = System.currentTimeMillis();
        int count = 0;
        for (Punishment p : punishments) {
            if (p.live(now) && (p.id.equalsIgnoreCase(q) || p.name.equalsIgnoreCase(q))) {
                p.active = false;
                count++;
            }
        }
        if (count == 0) { msg(s, "&7No active punishment found for &f" + a[0] + "&7."); return; }
        save();
        msg(s, "&aRemoved &f" + count + " &apunishment(s) for &f" + a[0] + "&a.");
        getLogger().info(s.getName() + " removed " + count + " punishment(s) for " + a[0]);
    }

    private void openOffends(Player staff, int page) {
        long now = System.currentTimeMillis();
        List<Punishment> live = new ArrayList<>();
        for (Punishment p : punishments) if (p.live(now)) live.add(p);
        live.sort(Comparator.comparingLong((Punishment p) -> p.created).reversed());
        int pages = Math.max(1, (live.size() + 44) / 45);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Menu m = new Menu(t("&cActive punishments &8(" + (pg + 1) + "/" + pages + ")"), 6);
        for (int i = 0; i < 45; i++) {
            int idx = pg * 45 + i;
            if (idx >= live.size()) break;
            Punishment pu = live.get(idx);
            String left = pu.until == 0 ? "permanent" : fmtDuration(pu.until - now);
            m.set(i, head(Bukkit.getOfflinePlayer(pu.uuid), "&c" + pu.name + " &7- " + pu.type, List.of(
                            "&7Reason: &f" + pu.reason, "&7Time left: &f" + left, "&7By: &f" + pu.staff, "&7ID: &f#" + pu.id,
                            "", "&eShift-click to remove")),
                    (pl, c) -> {
                        if (c == ClickType.SHIFT_LEFT || c == ClickType.SHIFT_RIGHT) {
                            pu.active = false;
                            save();
                            msg(pl, "&aRemoved #" + pu.id + " (" + pu.name + ").");
                            getLogger().info(pl.getName() + " removed punishment #" + pu.id + " (" + pu.name + ")");
                            openOffends(pl, pg);
                        }
                    });
        }
        nav(m, pg, pages, np -> openOffends(staff, np));
        m.open(staff);
    }

    // ---------------- chat prompts ----------------

    private void prompt(Player p, String message, Consumer<String> handler) {
        p.closeInventory();
        prompts.put(p.getUniqueId(), handler);
        msg(p, message);
    }

    // ---------------- settings: fast crystals, mob spawning ----------------

    private void toggleFastCrystals(UUID id) {
        if (!fastCrystals.remove(id)) fastCrystals.add(id);
        dirty = true;
    }

    /** With Fast Crystals on, hitting an end crystal destroys it and detonates it instantly. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onCrystalHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof EnderCrystal crystal) || !(e.getDamager() instanceof Player p)) return;
        if (!fastCrystals.contains(p.getUniqueId())) return;
        e.setCancelled(true);
        Location loc = crystal.getLocation();
        crystal.remove();
        loc.getWorld().createExplosion(loc, 6.0f, false, true, p);
    }

    /** Natural monster spawns are cancelled only when every player nearby has Mob Spawning off. */
    @EventHandler(ignoreCancelled = true)
    public void onMobSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL || !(e.getEntity() instanceof Monster)) return;
        var near = e.getLocation().getNearbyPlayers(96);
        if (near.isEmpty()) return;
        for (Player p : near) {
            if (!off(p.getUniqueId(), "mobs")) return;
        }
        e.setCancelled(true);
    }

    // ---------------- 54-slot ender chest ----------------

    private File echestFile(UUID u) { return new File(getDataFolder(), "echests/" + u + ".yml"); }

    private void openEchest(Player p) {
        EChestHolder h = new EChestHolder(p.getUniqueId());
        h.inv = Bukkit.createInventory(h, 54, t("&5Ender Chest"));
        File f = echestFile(p.getUniqueId());
        if (f.exists()) {
            List<?> list = YamlConfiguration.loadConfiguration(f).getList("items");
            if (list != null) {
                for (int i = 0; i < Math.min(54, list.size()); i++) {
                    if (list.get(i) instanceof ItemStack it) h.inv.setItem(i, it);
                }
            }
        } else {
            // first time: move whatever was in the vanilla ender chest into the new one
            ItemStack[] old = p.getEnderChest().getContents();
            for (int i = 0; i < old.length && i < 54; i++) {
                if (old[i] != null) h.inv.setItem(i, old[i]);
            }
            p.getEnderChest().clear();
            saveEchest(h);
        }
        p.openInventory(h.inv);
    }

    /** Serialises on the main thread, writes on one ordered background thread. */
    private void saveEchest(EChestHolder h) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("items", Arrays.asList(h.inv.getContents()));
        String data = y.saveToString();
        File f = echestFile(h.owner);
        try {
            io.execute(() -> {
                try {
                    Files.createDirectories(f.getParentFile().toPath());
                    Files.writeString(f.toPath(), data, StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    getLogger().severe("Could not save ender chest " + f.getName() + ": " + ex.getMessage());
                }
            });
        } catch (RuntimeException ex) {
            getLogger().severe("Ender chest save rejected: " + ex.getMessage());
        }
    }

    // saved after every click, so a crash can't leave the file out of step with the player's inventory
    @EventHandler
    public void onEchestClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder(false) instanceof EChestHolder h) {
            Bukkit.getScheduler().runTask(this, () -> saveEchest(h));
        }
    }

    @EventHandler
    public void onEchestDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof EChestHolder h) {
            Bukkit.getScheduler().runTask(this, () -> saveEchest(h));
        }
    }

    @EventHandler
    public void onEchestClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof EChestHolder h) saveEchest(h);
    }

    /** Right-clicking a placed ender chest block opens the big one (HIGH so crate chests still win). */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEnderChestBlock(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.ENDER_CHEST) return;
        if (e.getPlayer().isSneaking() && e.getItem() != null) return;   // let people place blocks against it
        e.setCancelled(true);
        openEchest(e.getPlayer());
    }

    // ---------------- /media ----------------

    private void openMedia(Player p) {
        Menu m = new Menu(t(getConfig().getString("media.title", "&dMedia Rank")), 3);
        List<String> reqs = getConfig().getStringList("media.requirements");
        if (reqs.isEmpty()) {
            reqs = List.of("10 average viewers on a live stream", "50 average viewers on a TikTok live",
                    "1,000 views on a YouTube video", "10,000 views on a TikTok, YouTube Short or Reel");
        }
        List<String> reminders = getConfig().getStringList("media.reminders");
        if (reminders.isEmpty()) {
            reminders = List.of("The content has to be on Goldrush", "It lasts 60 days and comes with VIP perks",
                    "Open a ticket on our Discord to apply");
        }
        List<String> lore = new ArrayList<>();
        lore.add("&dRequirements: &7(only one needed)");
        for (String r : reqs) lore.add("&f- " + r);
        lore.add("");
        lore.add("&dReminders:");
        for (String r : reminders) lore.add("&7- " + r);
        m.set(13, icon(Material.NETHER_STAR, "&d&lMedia Rank", lore), null);
        m.set(22, icon(Material.BOOK, "&bApply on Discord", List.of("&7Click for the Discord link")),
                (pl, c) -> { pl.closeInventory(); cmdDiscord(pl); });
        m.open(p);
    }

    // ---------------- teams ----------------

    private Team teamOf(UUID u) {
        String k = playerTeam.get(u);
        return k == null ? null : teams.get(k);
    }

    private void teamMsg(Team team, String legacy) {
        for (UUID u : team.members) {
            Player m = Bukkit.getPlayer(u);
            if (m != null) msg(m, legacy);
        }
    }

    private void createTeam(Player p, String name) {
        if (teamOf(p.getUniqueId()) != null) { msg(p, "&cYou're already in a team. Leave it first."); return; }
        if (!name.matches("[A-Za-z0-9]{3,12}")) { msg(p, "&cTeam names are 3-12 letters or numbers."); return; }
        String key = name.toLowerCase(Locale.ROOT);
        if (teams.containsKey(key)) { msg(p, "&cThat team name is taken."); return; }
        teams.put(key, new Team(name, p.getUniqueId()));
        playerTeam.put(p.getUniqueId(), key);
        dirty = true;
        msg(p, "&aTeam &f" + name + " &acreated. Invite players with &f/team&a.");
    }

    private void inviteToTeam(Player p, Player target) {
        Team team = teamOf(p.getUniqueId());
        if (team == null) { msg(p, "&cYou're not in a team."); return; }
        if (!team.owner.equals(p.getUniqueId())) { msg(p, "&cOnly the team owner can invite players."); return; }
        if (target.equals(p)) { msg(p, "&cYou're already in your team."); return; }
        if (teamOf(target.getUniqueId()) != null) { msg(p, "&c" + target.getName() + " is already in a team."); return; }
        if (team.members.size() >= getConfig().getInt("team.max-members", 8)) { msg(p, "&cYour team is full."); return; }
        if (!p.hasPermission("core.bypass") && ignores(target.getUniqueId(), p.getUniqueId())) {
            msg(p, "&c" + target.getName() + " isn't accepting invites.");
            return;
        }
        invites.computeIfAbsent(target.getUniqueId(), x -> new HashMap<>())
                .put(team.name.toLowerCase(Locale.ROOT), System.currentTimeMillis() + 300_000L);
        msg(p, "&aInvite sent to &f" + target.getName() + "&a.");
        target.sendMessage(t(PREFIX + "&f" + p.getName() + " &7invited you to team &b" + team.name + "&7. ")
                .append(Component.text("[ACCEPT]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/team accept " + team.name))));
    }

    private void acceptInvite(Player p, String teamName) {
        if (teamOf(p.getUniqueId()) != null) { msg(p, "&cYou're already in a team."); return; }
        Map<String, Long> inv = invites.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        String key = null;
        if (inv != null) {
            if (teamName != null) {
                String k = teamName.toLowerCase(Locale.ROOT);
                Long exp = inv.get(k);
                if (exp != null && now <= exp) key = k;
            } else {
                for (Map.Entry<String, Long> e : inv.entrySet()) {
                    if (now <= e.getValue()) { key = e.getKey(); break; }
                }
            }
        }
        Team team = key == null ? null : teams.get(key);
        if (team == null) { msg(p, "&cNo valid invite found."); return; }
        if (team.members.size() >= getConfig().getInt("team.max-members", 8)) { msg(p, "&cThat team is full."); return; }
        team.members.add(p.getUniqueId());
        playerTeam.put(p.getUniqueId(), key);
        invites.remove(p.getUniqueId());
        dirty = true;
        teamMsg(team, "&f" + p.getName() + " &ajoined the team!");
    }

    private void leaveTeam(Player p) {
        Team team = teamOf(p.getUniqueId());
        if (team == null) { msg(p, "&cYou're not in a team."); return; }
        UUID u = p.getUniqueId();
        team.members.remove(u);
        playerTeam.remove(u);
        teamChat.remove(u);
        if (team.members.isEmpty()) {
            teams.remove(team.name.toLowerCase(Locale.ROOT));
        } else {
            String extra = "";
            if (team.owner.equals(u)) {
                team.owner = team.members.iterator().next();
                extra = " &f" + name(team.owner) + " &7is the new owner.";
            }
            teamMsg(team, "&f" + p.getName() + " &7left the team." + extra);
        }
        dirty = true;
        msg(p, "&7You left the team.");
    }

    private void disbandTeam(Player p) {
        Team team = teamOf(p.getUniqueId());
        if (team == null) { msg(p, "&cYou're not in a team."); return; }
        if (!team.owner.equals(p.getUniqueId())) { msg(p, "&cOnly the owner can disband the team."); return; }
        teamMsg(team, "&cYour team &f" + team.name + " &chas been disbanded.");
        for (UUID u : team.members) {
            playerTeam.remove(u);
            teamChat.remove(u);
        }
        teams.remove(team.name.toLowerCase(Locale.ROOT));
        dirty = true;
    }

    private void kickFromTeam(Player p, UUID target) {
        Team team = teamOf(p.getUniqueId());
        if (team == null || !team.owner.equals(p.getUniqueId())) { msg(p, "&cOnly the team owner can kick."); return; }
        if (target.equals(p.getUniqueId()) || !team.members.contains(target)) { msg(p, "&cThat player isn't a kickable member."); return; }
        team.members.remove(target);
        playerTeam.remove(target);
        teamChat.remove(target);
        dirty = true;
        teamMsg(team, "&f" + name(target) + " &7was kicked from the team.");
        Player kicked = Bukkit.getPlayer(target);
        if (kicked != null) msg(kicked, "&cYou were kicked from the team &f" + team.name + "&c.");
    }

    private void setTeamHome(Player p) {
        Team team = teamOf(p.getUniqueId());
        if (team == null) { msg(p, "&cYou're not in a team."); return; }
        if (!team.owner.equals(p.getUniqueId())) { msg(p, "&cOnly the team owner can set the team home."); return; }
        team.home = p.getLocation();
        dirty = true;
        teamMsg(team, "&aThe team home was set by &f" + p.getName() + "&a.");
    }

    private void teamHome(Player p) {
        Team team = teamOf(p.getUniqueId());
        if (team == null) { msg(p, "&cYou're not in a team."); return; }
        if (team.home == null || team.home.getWorld() == null) { msg(p, "&cYour team has no home yet. The owner can set it with /team sethome."); return; }
        teleportWarmup(p, team.home, "the team home");
    }

    private void toggleTeamChat(Player p) {
        if (teamOf(p.getUniqueId()) == null) { msg(p, "&cYou're not in a team."); return; }
        boolean on = teamChat.add(p.getUniqueId());
        if (!on) teamChat.remove(p.getUniqueId());
        msg(p, on ? "&7Team chat: &aON &7(everything you type goes to your team)" : "&7Team chat: &cOFF");
    }

    private void toggleFriendlyFire(Player p) {
        Team team = teamOf(p.getUniqueId());
        if (team == null || !team.owner.equals(p.getUniqueId())) { msg(p, "&cOnly the team owner can change friendly fire."); return; }
        team.ff = !team.ff;
        dirty = true;
        teamMsg(team, "&7Friendly fire: " + (team.ff ? "&aON" : "&cOFF"));
    }

    private void sendTeamChat(Player from, String text) {
        Team team = teamOf(from.getUniqueId());
        if (team == null) { teamChat.remove(from.getUniqueId()); msg(from, "&cYou're not in a team."); return; }
        Punishment mute = activeMute(from.getUniqueId());
        if (mute != null) { notifyMuted(from, mute); return; }
        Component out = Component.text("[Team] ", NamedTextColor.AQUA)
                .append(Component.text(from.getName() + ": ", NamedTextColor.WHITE))
                .append(Component.text(text, NamedTextColor.GRAY));
        for (UUID u : team.members) {
            Player m = Bukkit.getPlayer(u);
            if (m != null) m.sendMessage(out);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onTeamDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player a) attacker = a;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player shooter) attacker = shooter;
        if (attacker == null || attacker.equals(victim)) return;
        String k1 = playerTeam.get(attacker.getUniqueId());
        if (k1 == null || !k1.equals(playerTeam.get(victim.getUniqueId()))) return;
        Team team = teams.get(k1);
        if (team != null && !team.ff) e.setCancelled(true);
    }

    private void cmdTc(CommandSender s, String[] a) {
        if (!isPlayer(s)) return;
        Player p = (Player) s;
        if (a.length == 0) { toggleTeamChat(p); return; }
        sendTeamChat(p, String.join(" ", a));
    }

    private void cmdTeam(CommandSender s, String[] a) {
        if (!isPlayer(s)) return;
        Player p = (Player) s;
        if (a.length == 0) { openTeamMenu(p); return; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (a.length < 2) {
                    prompt(p, "&7Type your team name in chat (3-12 letters or numbers, or type &fcancel&7).",
                            text -> { createTeam(p, text); openTeamMenu(p); });
                } else {
                    createTeam(p, a[1]);
                }
            }
            case "invite" -> {
                if (a.length < 2) {
                    openPicker(p, "&bInvite to team", 0, x -> teamOf(x.getUniqueId()) == null,
                            picked -> { p.closeInventory(); inviteToTeam(p, picked); });
                } else {
                    Player invited = Bukkit.getPlayerExact(a[1]);
                    if (invited == null) msg(p, "&c" + a[1] + " is not online."); else inviteToTeam(p, invited);
                }
            }
            case "accept" -> acceptInvite(p, a.length > 1 ? a[1] : null);
            case "leave" -> leaveTeam(p);
            case "disband" -> disbandTeam(p);
            case "kick" -> {
                if (a.length < 2) { msg(p, "&cUsage: /team kick <player>"); return; }
                UUID target = find(a[1]);
                if (target == null) msg(p, "&cUnknown player."); else kickFromTeam(p, target);
            }
            case "sethome" -> setTeamHome(p);
            case "home" -> teamHome(p);
            case "chat" -> toggleTeamChat(p);
            case "ff", "friendlyfire" -> toggleFriendlyFire(p);
            default -> openTeamMenu(p);
        }
    }

    private void openTeamMenu(Player p) {
        UUID id = p.getUniqueId();
        Team team = teamOf(id);
        if (team == null) {
            Menu m = new Menu(t("&bTeams"), 3);
            m.set(11, icon(Material.NAME_TAG, "&aCreate a team",
                            List.of("&7Click, then type a name in chat", "&73-12 letters or numbers")),
                    (pl, c) -> prompt(pl, "&7Type your team name in chat (or type &fcancel&7).",
                            text -> { createTeam(pl, text); openTeamMenu(pl); }));
            long now = System.currentTimeMillis();
            int slot = 18;
            for (Map.Entry<String, Long> e : invites.getOrDefault(id, Map.of()).entrySet()) {
                Team invited = teams.get(e.getKey());
                if (invited == null || now > e.getValue() || slot > 26) continue;
                m.set(slot++, icon(Material.PAPER, "&bInvite: &f" + invited.name,
                                List.of("&7Members: &f" + invited.members.size(), "&eClick to join")),
                        (pl, c) -> { acceptInvite(pl, invited.name); pl.closeInventory(); });
            }
            m.open(p);
            return;
        }

        boolean owner = team.owner.equals(id);
        Menu m = new Menu(t("&bTeam: &f" + team.name), 6);
        int i = 0;
        for (UUID u : team.members) {
            if (i >= 36) break;
            boolean isOwner = u.equals(team.owner);
            List<String> lore = new ArrayList<>(List.of(isOwner ? "&6Owner" : "&7Member",
                    Bukkit.getPlayer(u) != null ? "&aOnline" : "&7Offline"));
            if (owner && !u.equals(id)) lore.add("&cShift-click to kick");
            m.set(i++, head(Bukkit.getOfflinePlayer(u), "&f" + name(u), lore), (pl, c) -> {
                if (owner && (c == ClickType.SHIFT_LEFT || c == ClickType.SHIFT_RIGHT)) {
                    kickFromTeam(pl, u);
                    openTeamMenu(pl);
                }
            });
        }
        m.set(45, icon(Material.RED_BED, "&bTeam home",
                        List.of(team.home == null ? "&7Not set yet" : "&eClick to teleport")),
                (pl, c) -> { pl.closeInventory(); teamHome(pl); });
        if (owner) {
            m.set(46, icon(Material.WHITE_BED, "&aSet team home", List.of("&7Sets it to where you stand")),
                    (pl, c) -> { setTeamHome(pl); openTeamMenu(pl); });
            m.set(47, icon(Material.NAME_TAG, "&aInvite a player", List.of("&7Pick someone online")),
                    (pl, c) -> openPicker(pl, "&bInvite to team", 0, x -> teamOf(x.getUniqueId()) == null,
                            picked -> { pl.closeInventory(); inviteToTeam(pl, picked); }));
            m.set(49, icon(Material.IRON_SWORD, "&fFriendly fire: " + (team.ff ? "&aON" : "&cOFF"),
                            List.of("&7Click to toggle")),
                    (pl, c) -> { toggleFriendlyFire(pl); openTeamMenu(pl); });
        }
        m.set(48, icon(Material.WRITABLE_BOOK, "&fTeam chat: " + (teamChat.contains(id) ? "&aON" : "&cOFF"),
                        List.of("&7Click to toggle", "&7Or use &f/tc <message>")),
                (pl, c) -> { toggleTeamChat(pl); openTeamMenu(pl); });
        m.set(52, icon(Material.OAK_DOOR, "&cLeave team", List.of("&7Click to leave")),
                (pl, c) -> { leaveTeam(pl); pl.closeInventory(); });
        if (owner) {
            m.set(53, icon(Material.TNT, "&4Disband team", List.of("&cShift-click to disband")),
                    (pl, c) -> {
                        if (c == ClickType.SHIFT_LEFT || c == ClickType.SHIFT_RIGHT) { disbandTeam(pl); pl.closeInventory(); }
                    });
        }
        m.open(p);
    }

    // ---------------- /vanish ----------------

    private boolean canSeeVanished(CommandSender s) { return s.hasPermission("core.vanish.see"); }

    /** An online player by name, unless they are vanished and the asker may not see them. */
    private Player visibleOnline(CommandSender asker, String name) {
        Player t = Bukkit.getPlayerExact(name);
        if (t != null && vanished.contains(t.getUniqueId()) && !canSeeVanished(asker)) return null;
        return t;
    }

    private void cmdVanish(CommandSender s) {
        if (!isPlayer(s)) return;
        Player p = (Player) s;
        if (vanished.contains(p.getUniqueId())) unvanish(p); else vanish(p);
    }

    /** Hides p from everyone who is not allowed to see vanished staff, and makes p harmless to the world. */
    private void applyVanish(Player p) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!o.equals(p) && !canSeeVanished(o)) o.hidePlayer(this, p);
        }
        p.setCollidable(false);
        p.setSleepingIgnored(true);
        p.setCanPickupItems(false);
    }

    private void vanish(Player p) {
        vanished.add(p.getUniqueId());
        applyVanish(p);
        dirty = true;
        if (getConfig().getBoolean("vanish.fake-messages", true)) {
            Bukkit.broadcast(Component.translatable("multiplayer.player.left", Component.text(p.getName()))
                    .color(NamedTextColor.YELLOW));
        }
        msg(p, "&aYou are vanished. Nobody can see you, and a fake leave message was sent.");
    }

    private void unvanish(Player p) {
        vanished.remove(p.getUniqueId());
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!o.equals(p)) o.showPlayer(this, p);
        }
        p.setCollidable(true);
        p.setSleepingIgnored(false);
        p.setCanPickupItems(true);
        dirty = true;
        if (getConfig().getBoolean("vanish.fake-messages", true)) {
            Bukkit.broadcast(Component.translatable("multiplayer.player.joined", Component.text(p.getName()))
                    .color(NamedTextColor.YELLOW));
        }
        msg(p, "&7You are visible again.");
    }

    @EventHandler
    public void onVanishTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && vanished.contains(p.getUniqueId())) e.setCancelled(true);
    }

    // ---------------- /invsee ----------------

    private void cmdInvsee(CommandSender s, String[] a) {
        if (!isPlayer(s)) return;
        Player staff = (Player) s;
        if (a.length == 0) {
            openPicker(staff, "&bView an inventory", 0, x -> true, target -> openInvsee(staff, target));
            return;
        }
        Player target = Bukkit.getPlayerExact(a[0]);
        if (target == null) { msg(staff, "&c" + a[0] + " is not online."); return; }
        openInvsee(staff, target);
    }

    private static int targetSlot(int g) {
        if (g >= 0 && g <= 26) return 9 + g;       // main inventory
        if (g >= 27 && g <= 35) return g - 27;     // hotbar
        return switch (g) {
            case 36 -> 39;   // helmet
            case 37 -> 38;   // chestplate
            case 38 -> 37;   // leggings
            case 39 -> 36;   // boots
            case 40 -> 40;   // off hand
            default -> -1;
        };
    }

    private void fillInvsee(Menu m, Player target) {
        PlayerInventory pi = target.getInventory();
        for (int g = 0; g < 54; g++) {
            int ts = targetSlot(g);
            if (ts >= 0) m.inv.setItem(g, pi.getItem(ts));
            else if (g != 45) m.inv.setItem(g, icon(Material.BLACK_STAINED_GLASS_PANE, " ", null));
        }
        m.inv.setItem(45, head(target, "&b" + target.getName(), List.of(
                "&7Health: &f" + (int) target.getHealth(),
                "&7Gamemode: &f" + target.getGameMode().name().toLowerCase(Locale.ROOT))));
    }

    private void openInvsee(Player staff, Player target) {
        Menu m = new Menu(t("&bInventory: &f" + target.getName()), 6);
        boolean edit = staff.hasPermission("core.invsee.edit");
        m.raw = e -> {
            if (!edit) return;
            if (!target.isOnline()) { staff.closeInventory(); return; }
            int ts = targetSlot(e.getSlot());
            ClickType ct = e.getClick();
            if (ts < 0 || (ct != ClickType.LEFT && ct != ClickType.RIGHT)) return;
            // swap the cursor item with the real slot in one step, so nothing can be duplicated
            ItemStack cursor = e.getCursor();
            ItemStack cur = target.getInventory().getItem(ts);
            ItemStack put = (cursor == null || cursor.getType().isAir()) ? null : cursor.clone();
            ItemStack take = (cur == null || cur.getType().isAir()) ? null : cur.clone();
            target.getInventory().setItem(ts, put);
            staff.setItemOnCursor(take);
            getLogger().info(staff.getName() + " edited " + target.getName() + "'s inventory (slot " + ts + ")");
            fillInvsee(m, target);
        };
        m.refresh = () -> {
            if (!target.isOnline()) staff.closeInventory();
            else fillInvsee(m, target);
        };
        fillInvsee(m, target);
        m.open(staff);
    }
}
