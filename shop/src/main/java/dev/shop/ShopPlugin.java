package dev.shop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.meta.SkullMeta;
import java.util.function.Consumer;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Basic survival economy: /bal /pay /baltop /ah /orders /shardshop /keyall.
 * Self-contained (no Vault). Data lives in plugins/Shop/data.yml.
 */
public final class ShopPlugin extends JavaPlugin implements Listener {

    private static final String PREFIX = "&8[&6Shop&8] ";

    // ---------------- data ----------------
    private final Map<UUID, Double> bal = new HashMap<>();
    private final Map<UUID, Integer> shards = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<Integer, Listing> listings = new TreeMap<>();
    private final Map<Integer, Order> orders = new TreeMap<>();
    private final List<ShardItem> shardItems = new ArrayList<>();
    private final Map<String, Crate> crates = new LinkedHashMap<>();
    private final Map<String, String> crateBlocks = new HashMap<>();   // "world;x;y;z" -> crate id
    private NamespacedKey keyTag;
    private final Map<Material, Double> prices = new HashMap<>();          // /sell prices
    private final Map<UUID, Double> bounties = new HashMap<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private final Map<UUID, Long> playMinutes = new HashMap<>();
    private final Map<UUID, Integer> ahSort = new HashMap<>();
    private String shardTitle = "&bShard Shop";
    private int shardRows = 3;
    private final Map<UUID, Integer> points = new HashMap<>();
    private final Map<UUID, Long> boostUntil = new HashMap<>();
    private final Map<UUID, Integer> boostMult = new HashMap<>();
    private final Map<String, ShopCat> shopCats = new LinkedHashMap<>();
    private final Map<String, PointCat> pointCats = new LinkedHashMap<>();
    private String shopTitle = "&8\u25BA Shop &8| Menu";
    private String pointTitle = "&8\u25BA Point Shop &8| Menu";
    private int shopRows = 3;
    private int pointRows = 3;
    private volatile Map<Stat, List<String[]>> topRows = Map.of();
    private final Map<UUID, Map<String, Integer>> keys = new HashMap<>();   // virtual crate keys
    private int nextId = 1;
    private boolean dirty;
    private File dataFile;

    private static final class Listing {
        final int id; final UUID seller; final ItemStack item; final double price;
        Listing(int id, UUID seller, ItemStack item, double price) {
            this.id = id; this.seller = seller; this.item = item; this.price = price;
        }
    }

    private static final class Order {
        final int id; final UUID owner; final Material mat; final double price;
        int remaining; int collected;
        Order(int id, UUID owner, Material mat, int remaining, double price, int collected) {
            this.id = id; this.owner = owner; this.mat = mat;
            this.remaining = remaining; this.price = price; this.collected = collected;
        }
    }

    private record ShardItem(String name, Material mat, int amount, int cost, List<String> commands,
                             List<String> enchants, List<String> lore) { }

    private record Reward(double chance, Material icon, Material item, int amount, double money,
                          List<String> commands, String display, String spawner, int slot) { }

    private record Crate(String id, String name, Material keyMat, String keyName, List<Reward> rewards,
                         String title, String mode, int rows) {
        double total() {
            double t = 0;
            for (Reward r : rewards) t += r.chance();
            return t;
        }
    }

    private enum Type { AH, ORDERS, SHARDS }

    private static final class Gui implements InventoryHolder {
        final Type type; final int page; final int[] ids = new int[54]; Inventory inv;
        Gui(Type type, int page) { this.type = type; this.page = page; Arrays.fill(ids, -1); }
        @Override public Inventory getInventory() { return inv; }
    }

    // ---------------- lifecycle ----------------

    @Override
    public void onEnable() {
        ensureConfig();
        load();
        loadShardItems();
        keyTag = new NamespacedKey(this, "crate_key");
        loadCrates();
        loadPrices();
        loadShops();
        Bukkit.getPluginManager().registerEvents(this, this);
        for (String c : List.of("bal", "pay", "eco", "shards", "baltop", "ah", "orders", "shardshop", "keyall", "sb", "crate", "keys", "sell", "stats", "bounty", "shop", "points", "pointshop")) {
            Objects.requireNonNull(getCommand(c), c).setExecutor(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, () -> { if (dirty) save(); }, 100L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, this::updateBoards, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::playtimeTick, 1200L, 1200L);
        refreshTops();
        Bukkit.getScheduler().runTaskTimer(this, this::refreshTops, 600L, 600L);
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            GoldrushExpansion.install(this);
            getLogger().info("PlaceholderAPI found: %goldrush_...% placeholders are registered.");
        }
    }

    @Override
    public void onDisable() {
        save();
    }

    // ---------------- the single combined config.yml ----------------

    /** Written to plugins/Shop/config.yml on first start. */
    private static final String DEFAULT_CONFIG = """
# =====================================================================
#  GOLDRUSH SHOP - the one and only config file.
#  After editing, run /crate reload (no restart needed).
#
#  Sections: economy, ah, orders, keyall, bounty, scoreboard, points,
#            sell (/sell prices), crates, shardshop, shop (/shop), pointshop
# =====================================================================

config-version: 2

economy:
  start-balance: 500

ah:
  tax-percent: 5            # taken from the seller's payout
  max-listings-per-player: 10

orders:
  max-orders-per-player: 10

keyall:
  # "internal" = use the built-in crates (see the crates section below).
  # Or set a console command (run once per online player) for another crate plugin.
  # Placeholders: %player% %crate% %amount%
  command: "internal"
  broadcast: "&6&lKEYALL &e%amount%x &f%crate% &ekey(s) given to everyone online!"

bounty:
  min: 100                  # smallest bounty a player can place

server:
  ip: "goldrush.play.hosting"
  discord: "discord.gg/UAYr65u3wJ"

scoreboard:
  enabled: true
  title: "&e&lGOLDRUSH &f&lSMP"
  # Placeholders: %player% %money% %money_short% %shards% %points% %kills% %deaths%
  #               %playtime% %online% %ping% %bounty% %booster% %ip% %discord%
  # Up to 15 lines. "&8&m" followed by spaces draws a thin divider line.
  lines:
    - "&8&m                      "
    - "&6&l» &e&lPROFILE"
    - "&f  %player%"
    - "&6  Money: &a%money%"
    - "&b  Shards: &f%shards%"
    - "&d  Points: &f%points%"
    - "&8&m                      "
    - "&6&l» &e&lSTATS"
    - "&f  Kills: &c%kills% &8| &fDeaths: &c%deaths%"
    - "&f  Playtime: &e%playtime%"
    - "&8&m                      "
    - "&7Online: &f%online% &8| &7Ping: &a%ping%ms"
    - "&e%ip%"

tablist:
  enabled: true
  override-tab: false       # false = leave the tab list alone if the TAB plugin is installed
  header:
    - ""
    - "&e&lGOLDRUSH"
    - "&f&lSMP"
    - ""
    - "&7Your ping: &a%ping%ms"
    - ""
  footer:
    - ""
    - "&7IP: &e%ip%"
    - "&7Discord: &9%discord%"
    - ""
    - "&7Online: &f%online%"
    - ""

points:
  per-minute: 1             # points earned per minute online (boosters multiply this)

# ---------------------------------------------------------------------
#  /sell prices
# ---------------------------------------------------------------------
sell:
  # /sell prices.
  #
  # EVERY item you can get in survival is sellable automatically at default-price.
  # Items that can't be obtained in survival (barrier, bedrock, command blocks, spawners,
  # spawn eggs, infested blocks, ...) can never be sold.
  # Only plain items sell: named, enchanted or otherwise customised items are refused.
  #
  # This section only lists EXCEPTIONS:
  #   - put an item under "prices" to give it its own price (per item)
  #   - set a price to 0, or add it to "unsellable", to stop it being sold
  # After editing run /crate reload.

  default-price: 1

  unsellable: []
  #  - ELYTRA
  #  - TOTEM_OF_UNDYING

  prices:
    KELP: 100
    BAMBOO: 200
    PINK_PETALS: 400
    COBBLESTONE: 500
    STONE: 1.5
    DEEPSLATE: 1
    DIRT: 0.5
    OAK_LOG: 3
    SPRUCE_LOG: 3
    BIRCH_LOG: 3
    JUNGLE_LOG: 3
    ACACIA_LOG: 3
    DARK_OAK_LOG: 3
    COAL: 8
    RAW_IRON: 12
    IRON_INGOT: 18
    RAW_COPPER: 3
    COPPER_INGOT: 5
    RAW_GOLD: 20
    GOLD_INGOT: 30
    REDSTONE: 5
    LAPIS_LAZULI: 6
    QUARTZ: 8
    DIAMOND: 150
    EMERALD: 100
    NETHERITE_SCRAP: 400
    ANCIENT_DEBRIS: 450
    NETHERITE_INGOT: 2000
    OBSIDIAN: 25
    WHEAT: 3
    CARROT: 3
    POTATO: 3
    SUGAR_CANE: 3
    PUMPKIN: 5
    CACTUS: 3
    BONE: 3
    STRING: 4
    GUNPOWDER: 8
    SPIDER_EYE: 4
    FEATHER: 2
    LEATHER: 4
    ARROW: 2
    ENDER_PEARL: 50
    BLAZE_ROD: 30
    SLIME_BALL: 10
    COOKED_BEEF: 6
    COOKED_PORKCHOP: 6
    BREAD: 4
    GOLDEN_APPLE: 120

# ---------------------------------------------------------------------
#  Crates
# ---------------------------------------------------------------------
crates:
  # Each name below is a crate id. Keys are virtual (stored on the server, can't be duplicated):
  #   /crate give <player> <crate> [amount]     /keys shows how many you have
  #
  # mode:
  #   choose  (default) right-click the crate with a key -> a menu opens, you PICK the reward you want
  #   random  right-click -> a random reward is rolled (uses each reward's "chance")
  # Left-click on a crate block previews its rewards.
  #
  # Optional crate settings: title (menu title), rows (1-6, default = fits the rewards)
  #
  # Reward fields:
  #   item      a Minecraft item name, with amount (1-64)
  #   spawner   a mob name (ZOMBIE, BLAZE, ...): gives a spawner for that mob, with amount
  #   money     cash added to the player's balance
  #   commands  console commands, %player% is the winner
  #   display   optional text shown instead of the automatic name
  #   icon      optional item used as the menu icon
  #   slot      optional menu slot (0 = top left, 8 = top right, 9 = second row...)
  #   chance    weight, only used by random crates
  #
  # After editing run /crate reload.

  goldrush:
    name: "&e&lGOLDRUSH EXCLUSIVE"
    key-name: "&e&lGoldrush Key"
    title: "&e&lGOLDRUSH EXCLUSIVE"
    mode: choose
    rows: 1
    rewards:
      - {item: IRON_INGOT, amount: 16, slot: 0}
      - {item: IRON_INGOT, amount: 24, slot: 1}
      - {item: IRON_INGOT, amount: 32, slot: 2}
      - {item: IRON_INGOT, amount: 48, slot: 3}
      - {item: IRON_INGOT, amount: 64, slot: 4}
      - {item: GOLD_INGOT, amount: 16, slot: 5}
      - {item: GOLD_INGOT, amount: 32, slot: 6}
      - {item: GOLD_INGOT, amount: 64, slot: 7}
      - {item: GOLDEN_APPLE, amount: 16, slot: 8}

  spawner:
    name: "&bSpawner Crate"
    key-name: "&bSpawner Key"
    title: "&8► Spawner Crate &8| Menu"
    mode: choose
    rows: 4
    rewards:
      - {spawner: COW, amount: 3, slot: 4}
      - {spawner: PIG, amount: 3, slot: 11}
      - {spawner: ZOMBIE, amount: 3, slot: 12}
      - {spawner: SKELETON, amount: 3, slot: 13}
      - {spawner: SPIDER, amount: 3, slot: 14}
      - {spawner: BLAZE, amount: 3, slot: 15}
      - {spawner: IRON_GOLEM, amount: 3, slot: 22}

  starter:
    name: "&6Starter Crate"
    key-name: "&6Starter Key"
    mode: random
    rewards:
      - {chance: 40, item: IRON_INGOT, amount: 16}
      - {chance: 25, item: COOKED_BEEF, amount: 32}
      - {chance: 20, money: 2500, display: "$2,500"}
      - {chance: 10, item: DIAMOND, amount: 8}
      - {chance: 4, item: GOLDEN_APPLE, amount: 4}
      - {chance: 1, item: NETHERITE_INGOT, amount: 1}

# ---------------------------------------------------------------------
#  /shardshop
# ---------------------------------------------------------------------
shardshop:
  # The /shardshop menu. Items are placed in this order, left to right, top to bottom.
  #   name      display name (& colour codes)
  #   material  Minecraft item name
  #   amount    1-64
  #   cost      price in shards
  #   enchants  optional list "enchant:level" (levels above vanilla max are allowed)
  #   lore      optional extra lines under the name
  #   commands  optional console commands (%player% = buyer). If set, no item is given.
  # After editing run /crate reload.

  title: "&5Shop &8> &dShard Shop"
  rows: 6

  items:
    - name: "&5Shard Pickaxe"
      material: NETHERITE_PICKAXE
      amount: 1
      cost: 600
      enchants: ["efficiency:7", "unbreaking:5", "fortune:4", "mending:1"]
      lore: ["&7Forged from shards.", "&dMines like nothing else."]
      commands: []
    - name: "&5Shard Axe"
      material: NETHERITE_AXE
      amount: 1
      cost: 500
      enchants: ["efficiency:7", "unbreaking:5", "sharpness:6", "mending:1"]
      lore: ["&7Forged from shards."]
      commands: []
    - name: "&5Shard Shovel"
      material: NETHERITE_SHOVEL
      amount: 1
      cost: 400
      enchants: ["efficiency:7", "unbreaking:5", "mending:1"]
      lore: ["&7Forged from shards."]
      commands: []
    - name: "&5Shard Sword"
      material: NETHERITE_SWORD
      amount: 1
      cost: 700
      enchants: ["sharpness:6", "unbreaking:5", "looting:4", "fire_aspect:2", "sweeping_edge:4", "mending:1"]
      lore: ["&7Forged from shards."]
      commands: []
    - name: "&5Shard Helmet"
      material: NETHERITE_HELMET
      amount: 1
      cost: 600
      enchants: ["protection:5", "unbreaking:5", "respiration:4", "aqua_affinity:1", "mending:1"]
      lore: ["&7Forged from shards."]
      commands: []
    - name: "&5Shard Chestplate"
      material: NETHERITE_CHESTPLATE
      amount: 1
      cost: 800
      enchants: ["protection:5", "unbreaking:5", "mending:1"]
      lore: ["&7Forged from shards."]
      commands: []
    - name: "&5Shard Leggings"
      material: NETHERITE_LEGGINGS
      amount: 1
      cost: 700
      enchants: ["protection:5", "unbreaking:5", "mending:1"]
      lore: ["&7Forged from shards."]
      commands: []
    - name: "&5Shard Boots"
      material: NETHERITE_BOOTS
      amount: 1
      cost: 600
      enchants: ["protection:5", "unbreaking:5", "feather_falling:5", "depth_strider:3", "mending:1"]
      lore: ["&7Forged from shards."]
      commands: []
    - name: "&bDiamond Pack"
      material: DIAMOND
      amount: 16
      cost: 25
      commands: []
    - name: "&6Golden Apples"
      material: GOLDEN_APPLE
      amount: 8
      cost: 15
      commands: []
    - name: "&6Starter Crate Key"
      material: TRIPWIRE_HOOK
      amount: 1
      cost: 100
      commands:
        - "crate give %player% starter 1"

# ---------------------------------------------------------------------
#  /shop (buy with money)
# ---------------------------------------------------------------------
shop:
  min-markup: 1.1           # buy prices are never below an item's sell price times this
  # The /shop menu: buy items with MONEY. Items are listed as  ITEM_NAME: price-per-item
  #
  # Safety: a buy price can never be lower than an item's /sell price (times shop.min-markup in
  # config.yml, default 1.1). Otherwise players could buy and re-sell for free money.
  # If a price here is too low it is raised automatically and a warning is printed in the console.
  #
  # Category fields: slot, icon, name, description, info (lines), title (menu title), items.
  # "points: true" makes a category open the Point Shop instead.
  # After editing run /crate reload.

  title: "&8► Shop &8| Menu"
  rows: 3

  categories:
    blocks:
      slot: 10
      icon: OAK_LOG
      name: "&6&lBLOCKS"
      description: "Building blocks"
      info: ["Wood, stone, glass and more!"]
      title: "&8► Blocks &8| Menu"
      items:
        OAK_LOG: 12
        SPRUCE_LOG: 12
        BIRCH_LOG: 12
        DARK_OAK_LOG: 12
        STONE: 8
        DIRT: 4
        SAND: 6
        GLASS: 10
        BRICKS: 25
        SMOOTH_STONE: 12
        QUARTZ_BLOCK: 40
        WHITE_WOOL: 15
        TERRACOTTA: 15
    food:
      slot: 11
      icon: COOKED_BEEF
      name: "&c&lFOOD"
      description: "Stay fed"
      info: ["Steak, bread, golden apples..."]
      title: "&8► Food &8| Menu"
      items:
        COOKED_BEEF: 15
        COOKED_PORKCHOP: 15
        COOKED_SALMON: 12
        BAKED_POTATO: 10
        BREAD: 8
        PUMPKIN_PIE: 12
        GOLDEN_CARROT: 25
        GOLDEN_APPLE: 300
        CAKE: 40
    gear:
      slot: 12
      icon: TOTEM_OF_UNDYING
      name: "&e&lGEAR"
      description: "Tools, weapons and armor"
      info: ["Everything for a fight."]
      title: "&8► Gear &8| Menu"
      items:
        IRON_SWORD: 150
        IRON_PICKAXE: 150
        IRON_AXE: 150
        IRON_SHOVEL: 100
        IRON_HELMET: 200
        IRON_CHESTPLATE: 320
        IRON_LEGGINGS: 280
        IRON_BOOTS: 160
        SHIELD: 120
        BOW: 80
        ARROW: 4
        TOTEM_OF_UNDYING: 5000
    end:
      slot: 13
      icon: END_STONE
      name: "&d&lEND"
      description: "Blocks from the End"
      info: ["End stone, purpur and pearls."]
      title: "&8► End &8| Menu"
      items:
        END_STONE: 20
        END_STONE_BRICKS: 25
        PURPUR_BLOCK: 30
        CHORUS_FRUIT: 15
        ENDER_PEARL: 120
        ENDER_CHEST: 600
    nether:
      slot: 14
      icon: NETHERRACK
      name: "&4&lNETHER"
      description: "Blocks from the Nether"
      info: ["Netherrack, glowstone, blaze rods."]
      title: "&8► Nether &8| Menu"
      items:
        NETHERRACK: 6
        NETHER_BRICKS: 20
        SOUL_SAND: 15
        GLOWSTONE: 40
        QUARTZ: 20
        BASALT: 10
        BLACKSTONE: 12
        MAGMA_BLOCK: 30
        NETHER_WART: 25
        BLAZE_ROD: 80
    redstone:
      slot: 15
      icon: REDSTONE
      name: "&c&lREDSTONE"
      description: "Machines and contraptions"
      info: ["Pistons, hoppers, observers..."]
      title: "&8► Redstone &8| Menu"
      items:
        REDSTONE: 12
        REPEATER: 40
        COMPARATOR: 60
        PISTON: 60
        STICKY_PISTON: 90
        OBSERVER: 70
        HOPPER: 80
        DISPENSER: 70
        DROPPER: 60
        LEVER: 10
        REDSTONE_TORCH: 15
        TNT: 200
    points:
      slot: 16
      icon: DIAMOND
      name: "&b&lPOINTS"
      description: "The Point Shop"
      info:
        - "Spend your points here!"
        - "Colours, tags, keys, ranks and spawners"
      points: true

# ---------------------------------------------------------------------
#  /pointshop (buy with points)
# ---------------------------------------------------------------------
pointshop:
  # The Point Shop: spend POINTS (earned by playing). Open with /pointshop or the Points button in /shop.
  # Points: players earn points.per-minute (config.yml) every minute online, multiplied by boosters.
  #
  # Item fields:
  #   name      display name          icon     menu icon          cost    price in points
  #   lore      extra description lines
  #   item + amount     gives a plain item
  #   spawner + amount  gives a spawner for that mob
  #   commands  console commands run on purchase (%player% = buyer)
  #   booster   {multiplier: 2, minutes: 30} gives a points booster
  #
  # Colours / Tags / Ranks run whatever commands you write. The examples use LuckPerms permissions;
  # they only do something if you have a plugin that reads those permissions (chat colours, tags...).
  # After editing run /crate reload.

  title: "&8► Point Shop &8| Menu"
  rows: 3

  categories:
    colours:
      slot: 10
      icon: PINK_DYE
      name: "&d&lCOLOURS"
      description: "Chat colours"
      info: ["Colour your chat messages!"]
      title: "&8► Colours &8| Menu"
      items:
        - {name: "&cRed Chat", icon: RED_DYE, cost: 200, lore: ["&7Needs a chat-colour plugin"], commands: ["lp user %player% permission set goldrush.color.red true"]}
        - {name: "&aGreen Chat", icon: LIME_DYE, cost: 200, lore: ["&7Needs a chat-colour plugin"], commands: ["lp user %player% permission set goldrush.color.green true"]}
        - {name: "&bAqua Chat", icon: CYAN_DYE, cost: 200, lore: ["&7Needs a chat-colour plugin"], commands: ["lp user %player% permission set goldrush.color.aqua true"]}
    tags:
      slot: 11
      icon: NAME_TAG
      name: "&9&lTAGS"
      description: "Chat tags"
      info: ["Show a tag next to your name!"]
      title: "&8► Tags &8| Menu"
      items:
        - {name: "&6[Rich]", icon: GOLD_INGOT, cost: 500, lore: ["&7Needs a tags plugin"], commands: ["lp user %player% permission set goldrush.tag.rich true"]}
        - {name: "&c[Hunter]", icon: IRON_SWORD, cost: 500, lore: ["&7Needs a tags plugin"], commands: ["lp user %player% permission set goldrush.tag.hunter true"]}
    keys:
      slot: 12
      icon: TRIPWIRE_HOOK
      name: "&e&lKEYS"
      description: "Crate keys"
      info: ["Keys for every crate!"]
      title: "&8► Keys &8| Menu"
      items:
        - {name: "&6Starter Key", icon: TRIPWIRE_HOOK, cost: 50, commands: ["crate give %player% starter 1"]}
        - {name: "&e&lGoldrush Key", icon: TRIPWIRE_HOOK, cost: 150, commands: ["crate give %player% goldrush 1"]}
        - {name: "&bSpawner Key", icon: TRIPWIRE_HOOK, cost: 600, commands: ["crate give %player% spawner 1"]}
    ranks:
      slot: 13
      icon: NETHER_STAR
      name: "&6&lRANKS"
      description: "Server ranks"
      info: ["Permanent perks!"]
      title: "&8► Ranks &8| Menu"
      items:
        - {name: "&aVIP", icon: EMERALD, cost: 5000, lore: ["&7Run by LuckPerms: group vip must exist"], commands: ["lp user %player% parent add vip"]}
    spawners:
      slot: 14
      icon: SPAWNER
      name: "&5&lSPAWNERS"
      description: "Mob spawners"
      info: ["Pick the mob you want!"]
      title: "&8► Spawners &8| Menu"
      items:
        - {name: "&fCow Spawner", spawner: COW, amount: 1, cost: 300}
        - {name: "&fPig Spawner", spawner: PIG, amount: 1, cost: 300}
        - {name: "&2Zombie Spawner", spawner: ZOMBIE, amount: 1, cost: 800}
        - {name: "&7Skeleton Spawner", spawner: SKELETON, amount: 1, cost: 800}
        - {name: "&8Spider Spawner", spawner: SPIDER, amount: 1, cost: 800}
        - {name: "&6Blaze Spawner", spawner: BLAZE, amount: 1, cost: 2000}
    boosters:
      slot: 15
      icon: EXPERIENCE_BOTTLE
      name: "&e&lBOOSTERS"
      description: "Points boosters"
      info:
        - "Drink a Points Booster"
        - "and earn up to 3x points!"
      title: "&8► Boosters &8| Menu"
      items:
        - {name: "&e2x Booster &7(30 min)", icon: EXPERIENCE_BOTTLE, cost: 300, booster: {multiplier: 2, minutes: 30}}
        - {name: "&e3x Booster &7(30 min)", icon: EXPERIENCE_BOTTLE, cost: 600, booster: {multiplier: 3, minutes: 30}}
        - {name: "&e2x Booster &7(2 hours)", icon: EXPERIENCE_BOTTLE, cost: 1000, booster: {multiplier: 2, minutes: 120}}
""";

    /** Creates config.yml if missing. An old-format config.yml is kept as config-old.yml. */
    private void ensureConfig() {
        getDataFolder().mkdirs();
        File f = new File(getDataFolder(), "config.yml");
        boolean write = !f.exists();
        if (!write) {
            YamlConfiguration old = YamlConfiguration.loadConfiguration(f);
            if (!old.isConfigurationSection("crates") || !old.isConfigurationSection("sell")) {
                File backup = new File(getDataFolder(), "config-old.yml");
                if (backup.exists()) backup.delete();
                if (f.renameTo(backup)) {
                    getLogger().warning("Your old config.yml was moved to config-old.yml. A new combined config.yml was created.");
                    write = true;
                }
            }
        }
        if (write) {
            try {
                Files.writeString(f.toPath(), DEFAULT_CONFIG, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                getLogger().severe("Could not write config.yml: " + ex.getMessage());
            }
        }
        if (!write) migrateConfig(f);
        reloadConfig();
    }

    /** config-version 2 added the new scoreboard design, the tab list and the server section. */
    private void migrateConfig(File f) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        if (cfg.getInt("config-version", 1) >= 2) return;
        YamlConfiguration def = YamlConfiguration.loadConfiguration(new StringReader(DEFAULT_CONFIG));
        for (String sec : List.of("server", "scoreboard", "tablist")) {
            ConfigurationSection d = def.getConfigurationSection(sec);
            if (d == null) continue;
            cfg.set(sec, null);
            for (String k : d.getKeys(true)) {
                if (!d.isConfigurationSection(k)) cfg.set(sec + "." + k, d.get(k));
            }
        }
        cfg.set("config-version", 2);
        try {
            cfg.save(f);
            getLogger().info("config.yml updated: new scoreboard design, tab list and server section added.");
        } catch (IOException ex) {
            getLogger().severe("Could not update config.yml: " + ex.getMessage());
        }
    }

    // ---------------- persistence ----------------

    private void load() {
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        nextId = y.getInt("next-id", 1);
        ConfigurationSection bs = y.getConfigurationSection("bounties");
        if (bs != null) for (String k : bs.getKeys(false)) {
            try { bounties.put(UUID.fromString(k), bs.getDouble(k)); } catch (IllegalArgumentException ignoredEx) { }
        }
        loadInts(y, "kills", kills);
        loadInts(y, "deaths", deaths);
        loadInts(y, "points", points);
        ConfigurationSection bo = y.getConfigurationSection("boosters");
        if (bo != null) for (String k : bo.getKeys(false)) {
            try {
                UUID bu = UUID.fromString(k);
                boostUntil.put(bu, bo.getLong(k + ".until"));
                boostMult.put(bu, bo.getInt(k + ".mult", 1));
            } catch (IllegalArgumentException ignoredEx) { }
        }
        ConfigurationSection ps = y.getConfigurationSection("playtime");
        if (ps != null) for (String k : ps.getKeys(false)) {
            try { playMinutes.put(UUID.fromString(k), ps.getLong(k)); } catch (IllegalArgumentException ignoredEx) { }
        }
        for (String u : y.getStringList("sb-off")) {
            try { sbOff.add(UUID.fromString(u)); } catch (IllegalArgumentException ignored) { }
        }

        ConfigurationSection s = y.getConfigurationSection("balances");
        if (s != null) for (String k : s.getKeys(false)) bal.put(UUID.fromString(k), s.getDouble(k));
        s = y.getConfigurationSection("shards");
        if (s != null) for (String k : s.getKeys(false)) shards.put(UUID.fromString(k), s.getInt(k));
        s = y.getConfigurationSection("names");
        if (s != null) for (String k : s.getKeys(false)) names.put(UUID.fromString(k), s.getString(k, "Unknown"));

        for (String e : y.getStringList("crate-blocks")) {
            int i = e.lastIndexOf(';');
            if (i > 0) crateBlocks.put(e.substring(0, i), e.substring(i + 1));
        }
        for (String line : y.getStringList("crate-keys")) {
            try {
                String[] parts = line.split(";");
                if (parts.length == 3) {
                    keys.computeIfAbsent(UUID.fromString(parts[0]), x -> new HashMap<>())
                            .put(parts[1], Integer.parseInt(parts[2]));
                }
            } catch (IllegalArgumentException ignored) { }
        }
        s = y.getConfigurationSection("listings");
        if (s != null) for (String k : s.getKeys(false)) {
            try {
                ItemStack item = s.getItemStack(k + ".item");
                if (item == null) continue;
                int id = Integer.parseInt(k);
                listings.put(id, new Listing(id, UUID.fromString(Objects.requireNonNull(s.getString(k + ".seller"))),
                        item, s.getDouble(k + ".price")));
            } catch (Exception ex) {
                getLogger().warning("Skipping bad listing " + k + ": " + ex.getMessage());
            }
        }
        s = y.getConfigurationSection("orders");
        if (s != null) for (String k : s.getKeys(false)) {
            try {
                Material m = Material.matchMaterial(Objects.requireNonNull(s.getString(k + ".material")));
                if (m == null) continue;
                int id = Integer.parseInt(k);
                orders.put(id, new Order(id, UUID.fromString(Objects.requireNonNull(s.getString(k + ".owner"))), m,
                        s.getInt(k + ".remaining"), s.getDouble(k + ".price"), s.getInt(k + ".collected")));
            } catch (Exception ex) {
                getLogger().warning("Skipping bad order " + k + ": " + ex.getMessage());
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        bounties.forEach((k, v) -> y.set("bounties." + k, v));
        kills.forEach((k, v) -> y.set("kills." + k, v));
        deaths.forEach((k, v) -> y.set("deaths." + k, v));
        playMinutes.forEach((k, v) -> y.set("playtime." + k, v));
        points.forEach((k, v) -> y.set("points." + k, v));
        boostUntil.forEach((k, v) -> {
            y.set("boosters." + k + ".until", v);
            y.set("boosters." + k + ".mult", boostMult.getOrDefault(k, 1));
        });
        y.set("crate-blocks", crateBlocks.entrySet().stream().map(e -> e.getKey() + ";" + e.getValue()).toList());
        List<String> keyLines = new ArrayList<>();
        keys.forEach((u, m) -> m.forEach((id, n) -> keyLines.add(u + ";" + id + ";" + n)));
        y.set("crate-keys", keyLines);
        y.set("sb-off", sbOff.stream().map(UUID::toString).toList());
        bal.forEach((k, v) -> y.set("balances." + k, v));
        shards.forEach((k, v) -> y.set("shards." + k, v));
        names.forEach((k, v) -> y.set("names." + k, v));
        for (Listing l : listings.values()) {
            y.set("listings." + l.id + ".seller", l.seller.toString());
            y.set("listings." + l.id + ".item", l.item);
            y.set("listings." + l.id + ".price", l.price);
        }
        for (Order o : orders.values()) {
            y.set("orders." + o.id + ".owner", o.owner.toString());
            y.set("orders." + o.id + ".material", o.mat.name());
            y.set("orders." + o.id + ".remaining", o.remaining);
            y.set("orders." + o.id + ".price", o.price);
            y.set("orders." + o.id + ".collected", o.collected);
        }
        try {
            y.save(dataFile);
            dirty = false;
        } catch (IOException ex) {
            getLogger().severe("Could not save data.yml: " + ex.getMessage());
        }
    }

    private static void loadInts(YamlConfiguration y, String path, Map<UUID, Integer> out) {
        ConfigurationSection sec = y.getConfigurationSection(path);
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            try { out.put(UUID.fromString(k), sec.getInt(k)); } catch (IllegalArgumentException ignoredEx) { }
        }
    }

    private void loadShardItems() {
        shardItems.clear();
        ConfigurationSection cfg = getConfig().getConfigurationSection("shardshop");
        if (cfg == null) return;
        shardTitle = cfg.getString("title", "&bShard Shop");
        shardRows = Math.max(1, Math.min(6, cfg.getInt("rows", 6)));
        for (Map<?, ?> m : cfg.getMapList("items")) {
            Material mat = Material.matchMaterial(str(m, "material", "STONE"));
            if (mat == null || !mat.isItem() || mat.isAir()) {
                getLogger().warning("Shard shop: bad material " + m.get("material"));
                continue;
            }
            List<String> cmds = new ArrayList<>();
            if (m.get("commands") instanceof List<?> l) for (Object o : l) cmds.add(String.valueOf(o));
            List<String> enchants = new ArrayList<>();
            if (m.get("enchants") instanceof List<?> l) for (Object o : l) enchants.add(String.valueOf(o));
            List<String> lore = new ArrayList<>();
            if (m.get("lore") instanceof List<?> l) for (Object o : l) lore.add(String.valueOf(o));
            shardItems.add(new ShardItem(str(m, "name", "&fItem"), mat,
                    Math.max(1, Math.min(64, num(m, "amount", 1))), Math.max(0, num(m, "cost", 1)),
                    cmds, enchants, lore));
        }
    }

    private static String str(Map<?, ?> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : v.toString();
    }

    private static int num(Map<?, ?> m, String k, int def) {
        Object v = m.get(k);
        return v instanceof Number n ? n.intValue() : def;
    }

    // ---------------- economy helpers ----------------

    private double bal(UUID u) { return bal.getOrDefault(u, 0.0); }

    private boolean take(UUID u, double amt) {
        double b = bal(u);
        if (b + 1e-9 < amt) return false;
        bal.put(u, round(b - amt));
        dirty = true;
        return true;
    }

    private void add(UUID u, double amt) {
        bal.put(u, round(bal(u) + amt));
        dirty = true;
    }

    private int shards(UUID u) { return shards.getOrDefault(u, 0); }

    private boolean takeShards(UUID u, int amt) {
        if (shards(u) < amt) return false;
        shards.put(u, shards(u) - amt);
        dirty = true;
        return true;
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }

    private static String fmt(double v) { return "$" + String.format(Locale.US, "%,.2f", v); }

    /** Parses 1500, 1.5k, 2m, 3b. Throws NumberFormatException on anything invalid. */
    private static double parseAmount(String s, boolean allowZero) {
        s = s.toLowerCase(Locale.ROOT).replace(",", "");
        if (s.isEmpty()) throw new NumberFormatException();
        double mult = 1;
        char c = s.charAt(s.length() - 1);
        if (c == 'k') mult = 1e3; else if (c == 'm') mult = 1e6; else if (c == 'b') mult = 1e9;
        if (mult != 1) s = s.substring(0, s.length() - 1);
        double v = Double.parseDouble(s) * mult;
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0 || (v == 0 && !allowZero) || v > 1e12) {
            throw new NumberFormatException();
        }
        return round(v);
    }

    private UUID find(String name) {
        Player on = Bukkit.getPlayerExact(name);
        if (on != null) return on.getUniqueId();
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    private String name(UUID u) { return names.getOrDefault(u, "Unknown"); }

    // ---------------- item / message helpers ----------------

    private static Component line(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy).decoration(TextDecoration.ITALIC, false);
    }

    private static void msg(CommandSender s, String legacy) {
        s.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(PREFIX + legacy));
    }

    private static String pretty(Material m) { return m.name().toLowerCase(Locale.ROOT).replace('_', ' '); }

    private static ItemStack icon(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(line(name));
            if (lore != null) meta.lore(lore.stream().map(ShopPlugin::line).toList());
            it.setItemMeta(meta);
        }
        return it;
    }

    private static void giveItem(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
    }

    private static void giveMaterial(Player p, Material m, int count) {
        while (count > 0) {
            int n = Math.min(count, m.getMaxStackSize());
            giveItem(p, new ItemStack(m, n));
            count -= n;
        }
    }

    private static boolean plain(ItemStack it, Material m) {
        return it != null && it.getType() == m && !it.hasItemMeta();
    }

    // ---------------- commands ----------------

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        switch (c.getName().toLowerCase(Locale.ROOT)) {
            case "bal" -> cmdBal(s, a);
            case "pay" -> cmdPay(s, a);
            case "eco" -> cmdEco(s, a);
            case "shards" -> cmdShards(s, a);
            case "baltop" -> cmdBaltop(s);
            case "ah" -> cmdAh(s, a);
            case "orders" -> cmdOrders(s, a);
            case "shardshop" -> {
                if (s instanceof Player p) openShards(p); else msg(s, "&cPlayers only.");
            }
            case "keyall" -> cmdKeyall(s, a);
            case "sb" -> cmdSb(s);
            case "crate" -> cmdCrate(s, a);
            case "keys" -> cmdKeys(s);
            case "sell" -> {
                if (s instanceof Player sp) openSell(sp); else msg(s, "&cPlayers only.");
            }
            case "stats" -> {
                if (s instanceof Player sp) openStats(sp); else msg(s, "&cPlayers only.");
            }
            case "bounty" -> cmdBounty(s, a);
            case "shop" -> {
                if (s instanceof Player sp) openShop(sp); else msg(s, "&cPlayers only.");
            }
            case "pointshop" -> {
                if (s instanceof Player sp) openPointShop(sp); else msg(s, "&cPlayers only.");
            }
            case "points" -> cmdPoints(s, a);
            default -> { }
        }
        return true;
    }

    private void cmdBal(CommandSender s, String[] a) {
        if (a.length == 0) {
            if (!(s instanceof Player p)) { msg(s, "&cUsage: /bal <player>"); return; }
            msg(p, "&aBalance: &f" + fmt(bal(p.getUniqueId())));
            return;
        }
        UUID u = find(a[0]);
        if (u == null) { msg(s, "&cUnknown player."); return; }
        msg(s, "&a" + name(u) + "'s balance: &f" + fmt(bal(u)));
    }

    private void cmdPay(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { msg(s, "&cPlayers only."); return; }
        if (a.length < 2) { msg(p, "&cUsage: /pay <player> <amount>"); return; }
        UUID to = find(a[0]);
        if (to == null) { msg(p, "&cUnknown player."); return; }
        if (to.equals(p.getUniqueId())) { msg(p, "&cYou can't pay yourself."); return; }
        double amt;
        try { amt = parseAmount(a[1], false); } catch (NumberFormatException ex) { msg(p, "&cInvalid amount."); return; }
        if (!take(p.getUniqueId(), amt)) { msg(p, "&cNot enough money."); return; }
        add(to, amt);
        msg(p, "&aPaid &f" + fmt(amt) + " &ato &f" + name(to));
        Player tp = Bukkit.getPlayer(to);
        if (tp != null) msg(tp, "&aYou received &f" + fmt(amt) + " &afrom &f" + p.getName());
    }

    private void cmdEco(CommandSender s, String[] a) {
        if (a.length < 3) { msg(s, "&cUsage: /eco <give|take|set> <player> <amount>"); return; }
        UUID u = find(a[1]);
        if (u == null) { msg(s, "&cUnknown player (they must have joined once)."); return; }
        double amt;
        try { amt = parseAmount(a[2], true); } catch (NumberFormatException ex) { msg(s, "&cInvalid amount."); return; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "give" -> add(u, amt);
            case "take" -> { bal.put(u, Math.max(0, round(bal(u) - amt))); dirty = true; }
            case "set" -> { bal.put(u, amt); dirty = true; }
            default -> { msg(s, "&cUsage: /eco <give|take|set> <player> <amount>"); return; }
        }
        msg(s, "&a" + name(u) + " now has &f" + fmt(bal(u)));
    }

    private void cmdShards(CommandSender s, String[] a) {
        if (a.length == 0) {
            if (!(s instanceof Player p)) { msg(s, "&cUsage: /shards <give|take|set> <player> <amount>"); return; }
            msg(p, "&bShards: &f" + shards(p.getUniqueId()));
            return;
        }
        if (!s.hasPermission("shop.admin")) { msg(s, "&cNo permission."); return; }
        if (a.length < 3) { msg(s, "&cUsage: /shards <give|take|set> <player> <amount>"); return; }
        UUID u = find(a[1]);
        if (u == null) { msg(s, "&cUnknown player (they must have joined once)."); return; }
        int amt;
        try { amt = Integer.parseInt(a[2]); } catch (NumberFormatException ex) { msg(s, "&cInvalid amount."); return; }
        if (amt < 0) { msg(s, "&cInvalid amount."); return; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "give" -> shards.put(u, shards(u) + amt);
            case "take" -> shards.put(u, Math.max(0, shards(u) - amt));
            case "set" -> shards.put(u, amt);
            default -> { msg(s, "&cUsage: /shards <give|take|set> <player> <amount>"); return; }
        }
        dirty = true;
        msg(s, "&b" + name(u) + " now has &f" + shards(u) + " &bshards");
    }

    private void cmdBaltop(CommandSender s) {
        List<Map.Entry<UUID, Double>> l = new ArrayList<>(bal.entrySet());
        l.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
        msg(s, "&6Top balances");
        for (int i = 0; i < Math.min(10, l.size()); i++) {
            Map.Entry<UUID, Double> e = l.get(i);
            s.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&e#" + (i + 1) + " &f" + name(e.getKey()) + " &7- &a" + fmt(e.getValue())));
        }
    }

    private void cmdKeyall(CommandSender s, String[] a) {
        if (a.length < 1) { msg(s, "&cUsage: /keyall <crate> [amount]"); return; }
        String crate = a[0];
        if (!crate.matches("[A-Za-z0-9_\\-]{1,32}")) { msg(s, "&cInvalid crate name."); return; }
        int amount = 1;
        if (a.length > 1) {
            try { amount = Integer.parseInt(a[1]); } catch (NumberFormatException ex) { amount = -1; }
            if (amount < 1 || amount > 100) { msg(s, "&cAmount must be 1-100."); return; }
        }
        String tpl = getConfig().getString("keyall.command", "internal");
        boolean internal = tpl.isBlank() || tpl.equalsIgnoreCase("internal") || tpl.startsWith("crates key give");
        Crate internalCrate = crates.get(crate.toLowerCase(Locale.ROOT));
        if (internal && internalCrate == null) { msg(s, "&cUnknown crate. Use /crate list."); return; }
        for (Player pl : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (internal) {
                addKeys(pl.getUniqueId(), internalCrate.id(), amount);
                msg(pl, "&aYou received &f" + amount + "x &r" + internalCrate.keyName() + "&a. Right-click a crate to open it.");
                continue;
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), tpl
                    .replace("%player%", pl.getName())
                    .replace("%crate%", crate)
                    .replace("%amount%", String.valueOf(amount)));
        }
        String bc = getConfig().getString("keyall.broadcast", "");
        if (!bc.isBlank()) {
            Bukkit.broadcast(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    bc.replace("%crate%", crate).replace("%amount%", String.valueOf(amount))));
        }
    }

    // ---------------- auction house ----------------

    private void cmdAh(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { msg(s, "&cPlayers only."); return; }
        if (a.length == 0) { openAh(p, 0); return; }
        if (!a[0].equalsIgnoreCase("sell") || a.length < 2) { msg(p, "&cUsage: /ah [sell <price>]"); return; }

        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) { msg(p, "&cHold the item you want to sell."); return; }
        long mine = listings.values().stream().filter(l -> l.seller.equals(p.getUniqueId())).count();
        if (mine >= getConfig().getInt("ah.max-listings-per-player", 10)) {
            msg(p, "&cYou have too many active listings.");
            return;
        }
        double price;
        try { price = parseAmount(a[1], false); } catch (NumberFormatException ex) { msg(p, "&cInvalid price."); return; }

        Listing l = new Listing(nextId++, p.getUniqueId(), hand.clone(), price);
        listings.put(l.id, l);
        p.getInventory().setItemInMainHand(null);
        dirty = true;
        msg(p, "&aListed &f" + hand.getAmount() + "x " + pretty(hand.getType()) + " &afor &f" + fmt(price));
    }

    private void openAh(Player p, int page) {
        List<Listing> all = new ArrayList<>(listings.values());
        int sort = ahSort.getOrDefault(p.getUniqueId(), 0);
        if (sort == 0) all.sort(Comparator.comparingInt((Listing l) -> l.id).reversed());
        else if (sort == 1) all.sort(Comparator.comparingDouble((Listing l) -> l.price));
        else all.sort(Comparator.comparingDouble((Listing l) -> -l.price));
        int pages = Math.max(1, (all.size() + 44) / 45);
        page = Math.max(0, Math.min(page, pages - 1));
        Gui g = new Gui(Type.AH, page);
        g.inv = Bukkit.createInventory(g, 54, Component.text("Auction (Page " + (page + 1) + ")"));

        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= all.size()) break;
            Listing l = all.get(idx);
            ItemStack it = l.item.clone();
            ItemMeta m = it.getItemMeta();
            if (m != null) {
                List<Component> lore = m.lore() != null ? new ArrayList<>(m.lore()) : new ArrayList<>();
                lore.add(Component.empty());
                lore.add(line("&7Price: &a" + fmt(l.price)));
                lore.add(line("&7Seller: &f" + name(l.seller)));
                lore.add(line(l.seller.equals(p.getUniqueId()) ? "&eRight-click to cancel" : "&eClick to buy"));
                m.lore(lore);
                it.setItemMeta(m);
            }
            g.inv.setItem(i, it);
            g.ids[i] = l.id;
        }
        nav(g.inv, page, pages);
        String[] sortNames = {"Newest first", "Lowest price first", "Highest price first"};
        g.inv.setItem(48, icon(Material.HOPPER, "&eSort: &f" + sortNames[sort], List.of("&7Click to change")));
        p.openInventory(g.inv);
    }

    private void clickAh(Player p, int id, ClickType click) {
        Listing l = listings.get(id);
        if (l == null) { msg(p, "&cThat listing is gone."); return; }

        if (l.seller.equals(p.getUniqueId())) {
            if (click == ClickType.RIGHT) {
                listings.remove(id);
                giveItem(p, l.item);
                dirty = true;
                msg(p, "&aListing cancelled, item returned.");
            } else {
                msg(p, "&7Right-click your own listing to cancel it.");
            }
            return;
        }
        if (!take(p.getUniqueId(), l.price)) { msg(p, "&cNot enough money."); return; }

        listings.remove(id);
        double tax = Math.max(0, Math.min(100, getConfig().getDouble("ah.tax-percent", 5)));
        double payout = round(l.price * (1 - tax / 100.0));
        add(l.seller, payout);
        giveItem(p, l.item);
        msg(p, "&aBought &f" + l.item.getAmount() + "x " + pretty(l.item.getType()) + " &afor &f" + fmt(l.price));
        Player sp = Bukkit.getPlayer(l.seller);
        if (sp != null) {
            msg(sp, "&aYour &f" + pretty(l.item.getType()) + " &asold! You received &f" + fmt(payout));
        }
    }

    // ---------------- buy orders ----------------

    private void cmdOrders(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { msg(s, "&cPlayers only."); return; }
        if (a.length == 0) { openOrders(p, 0); return; }
        if (!a[0].equalsIgnoreCase("create") || a.length < 4) {
            msg(p, "&cUsage: /orders create <item> <amount> <priceEach>");
            return;
        }
        Material mat = Material.matchMaterial(a[1]);
        if (mat == null || mat.isLegacy() || !mat.isItem() || mat.isAir()) { msg(p, "&cUnknown item."); return; }
        int amount;
        double each;
        try {
            amount = Integer.parseInt(a[2]);
            each = parseAmount(a[3], false);
        } catch (NumberFormatException ex) {
            msg(p, "&cInvalid amount or price.");
            return;
        }
        if (amount < 1 || amount > 100_000) { msg(p, "&cAmount must be 1-100000."); return; }
        long mine = orders.values().stream().filter(o -> o.owner.equals(p.getUniqueId())).count();
        if (mine >= getConfig().getInt("orders.max-orders-per-player", 10)) {
            msg(p, "&cYou have too many active orders.");
            return;
        }
        double total = round(amount * each);
        if (!take(p.getUniqueId(), total)) { msg(p, "&cYou need " + fmt(total) + " to place this order."); return; }

        Order o = new Order(nextId++, p.getUniqueId(), mat, amount, each, 0);
        orders.put(o.id, o);
        dirty = true;
        msg(p, "&aOrder placed: &f" + amount + "x " + pretty(mat) + " &aat &f" + fmt(each) + " &aeach (" + fmt(total) + " held).");
    }

    private void openOrders(Player p, int page) {
        List<Order> all = new ArrayList<>(orders.values());
        all.sort(Comparator.comparingDouble((Order o) -> -o.price));
        int pages = Math.max(1, (all.size() + 44) / 45);
        page = Math.max(0, Math.min(page, pages - 1));
        Gui g = new Gui(Type.ORDERS, page);
        g.inv = Bukkit.createInventory(g, 54, Component.text("Orders (" + (page + 1) + "/" + pages + ")"));

        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= all.size()) break;
            Order o = all.get(idx);
            boolean own = o.owner.equals(p.getUniqueId());
            List<String> lore = new ArrayList<>();
            lore.add("&7Buyer: &f" + name(o.owner));
            lore.add("&7Wanted: &f" + o.remaining);
            lore.add("&7Price each: &a" + fmt(o.price));
            if (own) {
                lore.add("&7Collected: &f" + o.collected);
                lore.add("&eRight-click: claim items");
                lore.add("&eShift-right-click: cancel & refund");
            } else {
                lore.add("&eClick: sell your plain " + pretty(o.mat) + " to this order");
            }
            g.inv.setItem(i, icon(o.mat, "&e" + pretty(o.mat), lore));
            g.ids[i] = o.id;
        }
        nav(g.inv, page, pages);
        p.openInventory(g.inv);
    }

    private void clickOrder(Player p, int id, ClickType click) {
        Order o = orders.get(id);
        if (o == null) { msg(p, "&cThat order is gone."); return; }

        if (o.owner.equals(p.getUniqueId())) {
            if (click == ClickType.SHIFT_RIGHT) {
                add(p.getUniqueId(), round(o.remaining * o.price));
                if (o.collected > 0) giveMaterial(p, o.mat, o.collected);
                orders.remove(id);
                msg(p, "&aOrder cancelled. Refunded &f" + fmt(round(o.remaining * o.price)) + "&a and returned collected items.");
            } else if (click == ClickType.RIGHT) {
                if (o.collected == 0) { msg(p, "&7Nothing to claim yet."); return; }
                giveMaterial(p, o.mat, o.collected);
                msg(p, "&aClaimed &f" + o.collected + "x " + pretty(o.mat));
                o.collected = 0;
                if (o.remaining == 0) orders.remove(id);
            } else {
                msg(p, "&7Right-click to claim, shift-right-click to cancel.");
                return;
            }
            dirty = true;
            return;
        }

        // fill someone else's order
        ItemStack[] contents = p.getInventory().getStorageContents();
        int have = 0;
        for (ItemStack it : contents) if (plain(it, o.mat)) have += it.getAmount();
        int n = Math.min(have, o.remaining);
        if (n <= 0) { msg(p, "&cYou have no plain " + pretty(o.mat) + " to sell."); return; }

        int left = n;
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack it = contents[i];
            if (!plain(it, o.mat)) continue;
            int t = Math.min(left, it.getAmount());
            if (t == it.getAmount()) contents[i] = null; else it.setAmount(it.getAmount() - t);
            left -= t;
        }
        p.getInventory().setStorageContents(contents);

        double pay = round(n * o.price);
        add(p.getUniqueId(), pay);
        o.remaining -= n;
        o.collected += n;
        dirty = true;
        msg(p, "&aSold &f" + n + "x " + pretty(o.mat) + " &afor &f" + fmt(pay));
        Player owner = Bukkit.getPlayer(o.owner);
        if (owner != null) msg(owner, "&a" + p.getName() + " filled &f" + n + "x " + pretty(o.mat) + " &aof your order. Claim it in /orders.");
    }

    // ---------------- shard shop ----------------

    private ItemStack buildShardItem(ShardItem si) {
        ItemStack it = new ItemStack(si.mat(), si.amount());
        if (si.enchants().isEmpty() && si.lore().isEmpty()) return it;
        ItemMeta meta = it.getItemMeta();
        meta.displayName(line(si.name()));
        if (!si.lore().isEmpty()) meta.lore(si.lore().stream().map(ShopPlugin::line).toList());
        for (String spec : si.enchants()) {
            String[] parts = spec.split(":");
            Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(parts[0].trim().toLowerCase(Locale.ROOT)));
            int lvl = 1;
            if (parts.length > 1) {
                try { lvl = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException ex) { lvl = 1; }
            }
            if (en != null) meta.addEnchant(en, lvl, true);
            else getLogger().warning("Shard shop: unknown enchant " + parts[0]);
        }
        it.setItemMeta(meta);
        return it;
    }

    private void openShards(Player p) {
        Gui g = new Gui(Type.SHARDS, 0);
        g.inv = Bukkit.createInventory(g, shardRows * 9, LegacyComponentSerializer.legacyAmpersand().deserialize(shardTitle));
        for (int i = 0; i < Math.min(shardItems.size(), shardRows * 9); i++) {
            ShardItem si = shardItems.get(i);
            List<String> cost = List.of("&7Cost: &b" + si.cost() + " shards",
                    "&7You have: &b" + shards(p.getUniqueId()), "&eClick to buy");
            ItemStack it;
            if (si.enchants().isEmpty() && si.lore().isEmpty()) {
                it = icon(si.mat(), si.name(), cost);
                it.setAmount(si.amount());
            } else {
                it = buildShardItem(si);
                ItemMeta meta = it.getItemMeta();
                List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(Component.empty());
                for (String c : cost) lore.add(line(c));
                meta.lore(lore);
                it.setItemMeta(meta);
            }
            g.inv.setItem(i, it);
            g.ids[i] = i;
        }
        p.openInventory(g.inv);
    }

    private void clickShard(Player p, int idx) {
        if (idx < 0 || idx >= shardItems.size()) return;
        ShardItem si = shardItems.get(idx);
        if (!takeShards(p.getUniqueId(), si.cost())) {
            msg(p, "&cYou need &b" + si.cost() + " &cshards (you have " + shards(p.getUniqueId()) + ").");
            return;
        }
        if (si.commands().isEmpty()) {
            giveItem(p, buildShardItem(si));
        } else {
            for (String cmd : si.commands()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
            }
        }
        msg(p, "&aPurchased for &b" + si.cost() + " &ashards.");
    }

    // ---------------- sidebar scoreboard ----------------

    private static final int MAX_LINES = 15;
    private static final String[] ENTRIES = new String[MAX_LINES];
    static {
        for (int i = 0; i < MAX_LINES; i++) ENTRIES[i] = "\u00a7" + Integer.toHexString(i) + "\u00a7r";
    }

    private static final List<String> DEFAULT_SB_LINES = List.of(
            "&8&m                      ", "&6&l» &e&lPROFILE", "&f  %player%", "&6  Money: &a%money%",
            "&b  Shards: &f%shards%", "&d  Points: &f%points%", "&8&m                      ",
            "&6&l» &e&lSTATS", "&f  Kills: &c%kills% &8| &fDeaths: &c%deaths%", "&f  Playtime: &e%playtime%",
            "&8&m                      ", "&7Online: &f%online% &8| &7Ping: &a%ping%ms", "&e%ip%");
    private static final List<String> DEFAULT_TAB_HEADER = List.of("", "&e&lGOLDRUSH", "&f&lSMP", "", "&7Your ping: &a%ping%ms", "");
    private static final List<String> DEFAULT_TAB_FOOTER = List.of("", "&7IP: &e%ip%", "&7Discord: &9%discord%", "", "&7Online: &f%online%", "");

    private final Map<UUID, Integer> boardLines = new HashMap<>();
    private boolean tabNoticeShown;

    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Set<UUID> sbOff = new HashSet<>();

    private void cmdSb(CommandSender s) {
        if (!(s instanceof Player p)) { msg(s, "&cPlayers only."); return; }
        if (!sbOff.remove(p.getUniqueId())) sbOff.add(p.getUniqueId());
        dirty = true;
        msg(p, sbOff.contains(p.getUniqueId()) ? "&7Scoreboard hidden." : "&aScoreboard shown.");
        updateBoards();
    }

    /** 1234567 -> 1.2M, 999994429 -> 999.9M */
    private static String compact(double v) {
        String[] suffix = {"", "K", "M", "B", "T"};
        int i = 0;
        double x = v;
        while (Math.abs(x) >= 1000 && i < 4) { x /= 1000; i++; }
        if (i > 0 && Math.round(Math.abs(x) * 10) >= 10000 && i < 4) { x /= 1000; i++; }
        String out = i == 0 ? String.valueOf((long) x) : String.format(Locale.US, "%.1f", x);
        if (out.endsWith(".0")) out = out.substring(0, out.length() - 2);
        return out + suffix[i];
    }

    /** Fills the %placeholders% used by the scoreboard and the tab list. */
    private String sbText(String tpl, Player p) {
        UUID u = p.getUniqueId();
        return tpl.replace("%player%", p.getName())
                .replace("%money_short%", "$" + compact(bal(u)))
                .replace("%money%", "$" + String.format(Locale.US, "%,.0f", bal(u)))
                .replace("%shards%", compact(shards(u)))
                .replace("%points%", compact(points.getOrDefault(u, 0)))
                .replace("%kills%", String.valueOf(kills.getOrDefault(u, 0)))
                .replace("%deaths%", String.valueOf(deaths.getOrDefault(u, 0)))
                .replace("%playtime%", fmtStat(Stat.PLAYTIME, playMinutes.getOrDefault(u, 0L)))
                .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%ping%", String.valueOf(p.getPing()))
                .replace("%bounty%", fmt(bounties.getOrDefault(u, 0.0)))
                .replace("%booster%", boosterText(u))
                .replace("%ip%", getConfig().getString("server.ip", "goldrush.play.hosting"))
                .replace("%discord%", getConfig().getString("server.discord", "discord.gg/yourserver"));
    }

    private Scoreboard newBoard(int n) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = sb.registerNewObjective("shop", Criteria.DUMMY,
                line(getConfig().getString("scoreboard.title", "&e&lGOLDRUSH &f&lSMP")));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        obj.numberFormat(NumberFormat.blank());          // hides the red score numbers
        for (int i = 0; i < n; i++) {
            sb.registerNewTeam("l" + i).addEntry(ENTRIES[i]);
            obj.getScore(ENTRIES[i]).setScore(n - i);
        }
        return sb;
    }

    private void updateBoards() {
        boolean enabled = getConfig().getBoolean("scoreboard.enabled", true);
        List<String> lines = getConfig().getStringList("scoreboard.lines");
        if (lines.isEmpty()) lines = DEFAULT_SB_LINES;
        if (lines.size() > MAX_LINES) lines = lines.subList(0, MAX_LINES);
        String title = getConfig().getString("scoreboard.title", "&e&lGOLDRUSH &f&lSMP");

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            if (!enabled || sbOff.contains(u)) {
                if (boards.remove(u) != null) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                boardLines.remove(u);
                continue;
            }
            Scoreboard sb = boards.get(u);
            if (sb == null || boardLines.getOrDefault(u, -1) != lines.size()) {
                sb = newBoard(lines.size());
                boards.put(u, sb);
                boardLines.put(u, lines.size());
                p.setScoreboard(sb);
            }
            Objective obj = sb.getObjective("shop");
            if (obj != null) obj.displayName(line(title));
            for (int i = 0; i < lines.size(); i++) {
                Team t = sb.getTeam("l" + i);
                if (t != null) t.prefix(line(sbText(lines.get(i), p)));
            }
        }
        updateTablist();
    }

    private Component joinLines(List<String> tpl, Player p) {
        List<Component> parts = new ArrayList<>();
        for (String l : tpl) parts.add(LegacyComponentSerializer.legacyAmpersand().deserialize(sbText(l, p)));
        return Component.join(JoinConfiguration.newlines(), parts);
    }

    /** Sets the tab list header and footer (skipped if the TAB plugin owns the tab list). */
    private void updateTablist() {
        if (!getConfig().getBoolean("tablist.enabled", true)) return;
        if (Bukkit.getPluginManager().getPlugin("TAB") != null && !getConfig().getBoolean("tablist.override-tab", false)) {
            if (!tabNoticeShown) {
                tabNoticeShown = true;
                getLogger().info("TAB plugin found: leaving the tab list to TAB. Edit TAB's config, or set tablist.override-tab: true.");
            }
            return;
        }
        List<String> header = getConfig().getStringList("tablist.header");
        List<String> footer = getConfig().getStringList("tablist.footer");
        if (header.isEmpty()) header = DEFAULT_TAB_HEADER;
        if (footer.isEmpty()) footer = DEFAULT_TAB_FOOTER;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendPlayerListHeaderAndFooter(joinLines(header, p), joinLines(footer, p));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
        boardLines.remove(e.getPlayer().getUniqueId());
    }

    // ---------------- crates ----------------

    private static final class PreviewGui implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private static double dbl(Map<?, ?> m, String k, double def) {
        Object v = m.get(k);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    private static String key(Location l) {
        return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ();
    }

    private void loadCrates() {
        crates.clear();
        ConfigurationSection cfg = getConfig().getConfigurationSection("crates");
        if (cfg == null) return;

        for (String rawId : cfg.getKeys(false)) {
            if (!cfg.isConfigurationSection(rawId)) continue;
            String id = rawId.toLowerCase(Locale.ROOT);
            Material keyMat = Material.matchMaterial(cfg.getString(rawId + ".key-material", "TRIPWIRE_HOOK"));
            if (keyMat == null || !keyMat.isItem() || keyMat.isAir()) keyMat = Material.TRIPWIRE_HOOK;

            List<Reward> rewards = new ArrayList<>();
            for (Map<?, ?> m : cfg.getMapList(rawId + ".rewards")) {
                Material item = null;
                if (m.get("item") != null) {
                    item = Material.matchMaterial(str(m, "item", ""));
                    if (item == null || !item.isItem() || item.isAir()) {
                        getLogger().warning("Crate " + id + ": unknown item " + m.get("item") + ", reward skipped");
                        continue;
                    }
                }
                String spawner = null;
                if (m.get("spawner") != null) {
                    spawner = String.valueOf(m.get("spawner")).toUpperCase(Locale.ROOT);
                    try {
                        EntityType.valueOf(spawner);
                    } catch (IllegalArgumentException ex) {
                        getLogger().warning("Crate " + id + ": unknown mob " + m.get("spawner") + ", reward skipped");
                        continue;
                    }
                }
                double money = dbl(m, "money", 0);
                List<String> cmds = new ArrayList<>();
                if (m.get("commands") instanceof List<?> l) for (Object o : l) cmds.add(String.valueOf(o));
                if (item == null && spawner == null && money <= 0 && cmds.isEmpty()) {
                    getLogger().warning("Crate " + id + ": a reward gives nothing, skipped");
                    continue;
                }
                double chance = dbl(m, "chance", 1);
                if (chance <= 0) continue;
                Material icon = item != null ? item
                        : (spawner != null ? Material.SPAWNER : (money > 0 ? Material.GOLD_INGOT : Material.PAPER));
                if (m.get("icon") != null) {
                    Material custom = Material.matchMaterial(str(m, "icon", ""));
                    if (custom != null && custom.isItem() && !custom.isAir()) icon = custom;
                }
                String display = m.get("display") == null ? null : String.valueOf(m.get("display"));
                rewards.add(new Reward(chance, icon, item, Math.max(1, Math.min(64, num(m, "amount", 1))),
                        money, cmds, display, spawner, num(m, "slot", -1)));
            }
            String name = cfg.getString(rawId + ".name", rawId);
            crates.put(id, new Crate(id, name, keyMat, cfg.getString(rawId + ".key-name", "&eCrate Key"), rewards,
                    cfg.getString(rawId + ".title", "&8\u25BA " + name + " &8| Menu"),
                    cfg.getString(rawId + ".mode", "choose").toLowerCase(Locale.ROOT),
                    cfg.getInt(rawId + ".rows", 0)));
        }
        getLogger().info("Loaded " + crates.size() + " crate(s).");
    }

    private int keyCount(UUID u, String crateId) {
        Map<String, Integer> m = keys.get(u);
        return m == null ? 0 : m.getOrDefault(crateId, 0);
    }

    private void addKeys(UUID u, String crateId, int n) {
        keys.computeIfAbsent(u, x -> new HashMap<>()).merge(crateId, n, Integer::sum);
        dirty = true;
    }

    private void useKey(UUID u, String crateId) {
        Map<String, Integer> m = keys.get(u);
        if (m == null) return;
        m.computeIfPresent(crateId, (k, v) -> v <= 1 ? null : v - 1);
        dirty = true;
    }

    private void cmdKeys(CommandSender s) {
        if (!(s instanceof Player p)) { msg(s, "&cPlayers only."); return; }
        boolean any = false;
        for (Crate c : crates.values()) {
            int n = keyCount(p.getUniqueId(), c.id());
            if (n > 0) { msg(p, "&f" + n + "x " + c.keyName()); any = true; }
        }
        if (!any) msg(p, "&7You have no crate keys.");
    }

    private static String titleCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.toLowerCase(Locale.ROOT).split("[_ ]")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private static String rewardLabel(Reward r) {
        if (r.display() != null) return r.display();
        if (r.spawner() != null) return r.amount() + "x " + titleCase(r.spawner()) + " Spawner";
        if (r.item() != null) return r.amount() + "x " + pretty(r.item());
        if (r.money() > 0) return fmt(r.money());
        return "a special reward";
    }

    private void cmdCrate(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> msg(s, "&6Crates: &f" + (crates.isEmpty()
                    ? "none yet (edit the crates section of config.yml, then /crate reload)" : String.join(", ", crates.keySet())));
            case "reload" -> {
                reloadConfig();
                loadShardItems();
                loadCrates();
                loadPrices();
                loadShops();
                msg(s, "&aReloaded config.yml (" + crates.size() + " crates).");
            }
            case "give" -> {
                if (a.length < 3) { msg(s, "&cUsage: /crate give <player> <crate> [amount]"); return; }
                UUID u = find(a[1]);
                Crate c = crates.get(a[2].toLowerCase(Locale.ROOT));
                if (u == null) { msg(s, "&cUnknown player (they must have joined once)."); return; }
                if (c == null) { msg(s, "&cUnknown crate. Use /crate list."); return; }
                int amt = 1;
                if (a.length > 3) {
                    try { amt = Integer.parseInt(a[3]); } catch (NumberFormatException ex) { amt = -1; }
                    if (amt < 1 || amt > 100000) { msg(s, "&cAmount must be 1-100000."); return; }
                }
                addKeys(u, c.id(), amt);
                msg(s, "&aGave &f" + amt + "x " + c.id() + " &akey(s) to &f" + name(u));
                Player t = Bukkit.getPlayer(u);
                if (t != null) msg(t, "&aYou received &f" + amt + "x &r" + c.keyName() + "&a. Right-click a crate to open it.");
            }
            case "set", "remove" -> {
                if (!(s instanceof Player staff)) { msg(s, "&cPlayers only."); return; }
                Block b = staff.getTargetBlockExact(6);
                if (b == null || b.getType().isAir()) { msg(s, "&cLook at a block (within 6 blocks) first."); return; }
                if (sub.equals("remove")) {
                    boolean had = crateBlocks.remove(key(b.getLocation())) != null;
                    dirty = true;
                    msg(s, had ? "&aCrate removed from that block." : "&7That block isn't a crate.");
                    return;
                }
                if (a.length < 2) { msg(s, "&cUsage: /crate set <crate> (while looking at a chest or barrel)"); return; }
                Crate c = crates.get(a[1].toLowerCase(Locale.ROOT));
                if (c == null) { msg(s, "&cUnknown crate. Use /crate list."); return; }
                crateBlocks.put(key(b.getLocation()), c.id());
                dirty = true;
                msg(s, "&aThat block is now a &f" + c.id() + " &acrate. Left-click previews the rewards, right-click opens it (uses one key).");
            }
            default -> msg(s, "&cUsage: /crate <give|set|remove|list|reload> ...");
        }
    }

    @EventHandler
    public void onCrateInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action act = e.getAction();
        if (act != Action.RIGHT_CLICK_BLOCK && act != Action.LEFT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        String crateId = crateBlocks.get(key(b.getLocation()));
        if (crateId == null) return;

        e.setCancelled(true);
        Player p = e.getPlayer();
        Crate c = crates.get(crateId);
        if (c == null || c.rewards().isEmpty()) { msg(p, "&cThis crate has no rewards set up."); return; }

        if (act == Action.LEFT_CLICK_BLOCK) { openCrateMenu(p, c, false); return; }
        if (keyCount(p.getUniqueId(), c.id()) < 1) {
            msg(p, "&cYou have no " + c.keyName() + "&c. &7Left-click the crate to preview the rewards.");
            return;
        }
        if (c.mode().equals("random")) openRandomCrate(p, c); else openCrateMenu(p, c, true);
    }

    private ItemStack spawnerItem(String mob, int amount) {
        ItemStack it = new ItemStack(Material.SPAWNER, Math.max(1, Math.min(64, amount)));
        BlockStateMeta meta = (BlockStateMeta) it.getItemMeta();
        CreatureSpawner cs = (CreatureSpawner) meta.getBlockState();
        cs.setSpawnedType(EntityType.valueOf(mob));
        meta.setBlockState(cs);
        meta.displayName(line("&b" + titleCase(mob) + " Spawner"));
        it.setItemMeta(meta);
        return it;
    }

    /** The item shown in the menu for a reward (and handed over for spawners). */
    private ItemStack rewardItem(Reward r) {
        if (r.spawner() != null) return spawnerItem(r.spawner(), r.amount());
        return new ItemStack(r.icon(), r.item() != null ? r.amount() : 1);
    }

    private void giveReward(Player p, Reward r) {
        if (r.spawner() != null) giveItem(p, spawnerItem(r.spawner(), r.amount()));
        else if (r.item() != null) giveMaterial(p, r.item(), r.amount());
        if (r.money() > 0) add(p.getUniqueId(), r.money());
        for (String cmd : r.commands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        }
    }

    private void openRandomCrate(Player p, Crate c) {
        useKey(p.getUniqueId(), c.id());   // take the key first, then roll
        double roll = ThreadLocalRandom.current().nextDouble() * c.total();
        Reward win = c.rewards().get(c.rewards().size() - 1);
        double acc = 0;
        for (Reward r : c.rewards()) {
            acc += r.chance();
            if (roll < acc) { win = r; break; }
        }
        giveReward(p, win);
        msg(p, "&aYou opened a " + c.name() + "&a and got: &f" + rewardLabel(win));
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    /** claim = true: the player picks one reward (uses a key). claim = false: preview only. */
    private void openCrateMenu(Player p, Crate c, boolean claim) {
        int count = c.rewards().size();
        Set<Integer> used = new HashSet<>();
        int[] slots = new int[count];
        int maxSlot = 0;
        for (int i = 0; i < count; i++) {
            int want = c.rewards().get(i).slot();
            slots[i] = (want >= 0 && want < 54 && used.add(want)) ? want : -1;
            if (slots[i] >= 0) maxSlot = Math.max(maxSlot, slots[i]);
        }
        int next = 0;
        for (int i = 0; i < count; i++) {
            if (slots[i] >= 0) continue;
            while (used.contains(next)) next++;
            slots[i] = next;
            used.add(next);
            maxSlot = Math.max(maxSlot, next);
        }
        int rows = c.rows() > 0 ? Math.min(6, c.rows()) : Math.max(1, Math.min(6, maxSlot / 9 + 1));
        AMenu m = new AMenu(LegacyComponentSerializer.legacyAmpersand().deserialize(c.title()), rows);
        double total = c.total();
        boolean random = c.mode().equals("random");

        for (int i = 0; i < count; i++) {
            if (slots[i] >= rows * 9) continue;
            Reward r = c.rewards().get(i);
            ItemStack it = rewardItem(r);
            ItemMeta meta = it.getItemMeta();
            List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            if (random) lore.add(line("&7Chance: &e" + String.format(Locale.US, "%.1f", r.chance() / total * 100) + "%"));
            lore.add(line(claim ? "&aClick to claim this reward" : "&7Preview. Right-click the crate with a key."));
            meta.lore(lore);
            if (r.display() != null) meta.displayName(line("&f" + r.display()));
            it.setItemMeta(meta);
            m.set(slots[i], it, claim ? ct -> claimReward(p, c, r) : null);
        }
        p.openInventory(m.inv);
    }

    private void claimReward(Player p, Crate c, Reward r) {
        if (keyCount(p.getUniqueId(), c.id()) < 1) {
            msg(p, "&cYou have no keys left for this crate.");
            p.closeInventory();
            return;
        }
        useKey(p.getUniqueId(), c.id());
        giveReward(p, r);
        msg(p, "&aYou opened a " + c.name() + "&a and chose: &f" + rewardLabel(r));
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        p.closeInventory();
    }

    @EventHandler
    public void onPreviewClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof PreviewGui) e.setCancelled(true);
    }

    @EventHandler
    public void onPreviewDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof PreviewGui) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCrateBreak(BlockBreakEvent e) {
        if (crateBlocks.containsKey(key(e.getBlock().getLocation()))) {
            e.setCancelled(true);
            msg(e.getPlayer(), "&cThis is a crate. Admins: look at it and use /crate remove.");
        }
    }

    @EventHandler
    public void onCrateExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> crateBlocks.containsKey(key(b.getLocation())));
    }

    @EventHandler
    public void onCrateBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> crateBlocks.containsKey(key(b.getLocation())));
    }

    // ---------------- generic action menu (stats, bounty) ----------------

    private static final class AMenu implements InventoryHolder {
        final Inventory inv;
        final Map<Integer, Consumer<ClickType>> actions = new HashMap<>();
        AMenu(Component title, int rows) { inv = Bukkit.createInventory(this, rows * 9, title); }
        void set(int slot, ItemStack it, Consumer<ClickType> action) {
            inv.setItem(slot, it);
            if (action != null) actions.put(slot, action);
        }
        @Override public Inventory getInventory() { return inv; }
    }

    @EventHandler
    public void onAMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof AMenu m)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;
        Consumer<ClickType> action = m.actions.get(e.getSlot());
        if (action == null) return;
        ClickType ct = e.getClick();
        Bukkit.getScheduler().runTask(this, () -> action.accept(ct));
    }

    @EventHandler
    public void onAMenuDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof AMenu) e.setCancelled(true);
    }

    private static ItemStack head(OfflinePlayer op, String name, List<String> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        meta.setOwningPlayer(op);
        meta.displayName(line(name));
        if (lore != null) meta.lore(lore.stream().map(ShopPlugin::line).toList());
        it.setItemMeta(meta);
        return it;
    }

    // ---------------- /sell ----------------

    private static final class SellGui implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    /** Items that can't be obtained in normal survival: never sellable. */
    private static final Set<String> UNOBTAINABLE = Set.of(
            "BARRIER", "BEDROCK", "LIGHT", "STRUCTURE_VOID", "STRUCTURE_BLOCK", "JIGSAW", "DEBUG_STICK",
            "KNOWLEDGE_BOOK", "COMMAND_BLOCK_MINECART", "SPAWNER", "TRIAL_SPAWNER", "VAULT",
            "REINFORCED_DEEPSLATE", "END_PORTAL_FRAME", "BUDDING_AMETHYST", "CHORUS_PLANT", "FARMLAND",
            "PLAYER_HEAD", "PETRIFIED_OAK_SLAB", "SUSPICIOUS_SAND", "SUSPICIOUS_GRAVEL", "TEST_BLOCK",
            "TEST_INSTANCE_BLOCK", "WRITTEN_BOOK", "FILLED_MAP");

    private static boolean obtainable(Material m) {
        if (m.isLegacy() || !m.isItem() || m.isAir()) return false;
        String n = m.name();
        return !(UNOBTAINABLE.contains(n) || n.endsWith("_SPAWN_EGG") || n.startsWith("INFESTED_")
                || n.endsWith("COMMAND_BLOCK"));
    }

    /**
     * Every survival-obtainable item is sellable at default-price.
     * the sell section of config.yml only lists exceptions (a different price, or 0 to block an item).
     */
    private void loadPrices() {
        prices.clear();
        ConfigurationSection cfg = getConfig().getConfigurationSection("sell");
        if (cfg == null) { getLogger().warning("config.yml has no sell section."); return; }
        double def = cfg.getDouble("default-price", 1.0);

        Set<Material> blocked = new HashSet<>();
        for (String k : cfg.getStringList("unsellable")) {
            Material m = Material.matchMaterial(k);
            if (m != null) blocked.add(m); else getLogger().warning("config.yml (sell): unknown item in unsellable: " + k);
        }
        Map<Material, Double> custom = new HashMap<>();
        ConfigurationSection sec = cfg.getConfigurationSection("prices");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m == null) { getLogger().warning("config.yml (sell): unknown item " + k); continue; }
                custom.put(m, sec.getDouble(k));
            }
        }
        for (Material m : Material.values()) {
            if (!obtainable(m) || blocked.contains(m)) continue;
            Double c = custom.get(m);
            double price = c != null ? c : def;
            if (price > 0) prices.put(m, price);
        }
        getLogger().info("Sell prices loaded: " + prices.size() + " sellable items.");
    }

    private boolean sellable(ItemStack it) {
        return it != null && !it.hasItemMeta() && prices.containsKey(it.getType());
    }

    private double sellTotal(SellGui g) {
        double total = 0;
        for (int i = 0; i < 45; i++) {
            ItemStack it = g.inv.getItem(i);
            if (sellable(it)) total += prices.get(it.getType()) * it.getAmount();
        }
        return round(total);
    }

    private void updateSellButton(SellGui g) {
        g.inv.setItem(49, icon(Material.LIME_STAINED_GLASS_PANE, "&aSell everything for &f" + fmt(sellTotal(g)),
                List.of("&7Put items in the empty slots above,", "&7then click here to sell them.",
                        "&7Items that can't be sold come back", "&7when you close this menu.")));
    }

    private void openSell(Player p) {
        SellGui g = new SellGui();
        g.inv = Bukkit.createInventory(g, 54, Component.text("Sell Items"));
        for (int i = 45; i < 54; i++) g.inv.setItem(i, icon(Material.BLACK_STAINED_GLASS_PANE, " ", null));
        updateSellButton(g);
        p.openInventory(g.inv);
    }

    private void sellFrom(Player p, SellGui g) {
        double total = 0;
        int count = 0;
        for (int i = 0; i < 45; i++) {
            ItemStack it = g.inv.getItem(i);
            if (!sellable(it)) continue;
            total += prices.get(it.getType()) * it.getAmount();
            count += it.getAmount();
            g.inv.setItem(i, null);
        }
        if (count == 0) { msg(p, "&cNothing sellable in the menu."); return; }
        total = round(total);
        add(p.getUniqueId(), total);
        msg(p, "&aSold &f" + count + " &aitems for &f" + fmt(total) + "&a.");
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        updateSellButton(g);
    }

    @EventHandler
    public void onSellClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof SellGui g)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        boolean top = e.getClickedInventory() == e.getInventory();
        if (top && e.getSlot() >= 45) {
            e.setCancelled(true);
            if (e.getSlot() == 49) sellFrom(p, g);
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> updateSellButton(g));
    }

    @EventHandler
    public void onSellDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof SellGui g)) return;
        for (int raw : e.getRawSlots()) {
            if (raw >= 45 && raw < 54) { e.setCancelled(true); return; }
        }
        Bukkit.getScheduler().runTask(this, () -> updateSellButton(g));
    }

    @EventHandler
    public void onSellClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof SellGui g)) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        for (int i = 0; i < 45; i++) {
            ItemStack it = g.inv.getItem(i);
            if (it != null && !it.getType().isAir()) {
                giveItem(p, it);
                g.inv.setItem(i, null);
            }
        }
    }

    // ---------------- /bounty ----------------

    private void cmdBounty(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { msg(s, "&cPlayers only."); return; }
        if (a.length == 0) { openBounties(p); return; }
        if (!a[0].equalsIgnoreCase("add") || a.length < 3) { msg(p, "&cUsage: /bounty add <player> <amount>"); return; }
        UUID target = find(a[1]);
        if (target == null) { msg(p, "&cUnknown player."); return; }
        if (target.equals(p.getUniqueId())) { msg(p, "&cYou can't place a bounty on yourself."); return; }
        double amt;
        try { amt = parseAmount(a[2], false); } catch (NumberFormatException ex) { msg(p, "&cInvalid amount."); return; }
        double min = getConfig().getDouble("bounty.min", 100);
        if (amt < min) { msg(p, "&cThe minimum bounty is " + fmt(min) + "."); return; }
        if (!take(p.getUniqueId(), amt)) { msg(p, "&cNot enough money."); return; }
        double total = bounties.merge(target, amt, Double::sum);
        dirty = true;
        Bukkit.broadcast(LegacyComponentSerializer.legacyAmpersand().deserialize(
                "&6&lBOUNTY &f" + p.getName() + " &eplaced a &a" + fmt(amt) + " &ebounty on &c" + name(target)
                        + "&e! Total: &a" + fmt(total)));
    }

    private void openBounties(Player p) {
        List<Map.Entry<UUID, Double>> list = new ArrayList<>(bounties.entrySet());
        list.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
        AMenu m = new AMenu(Component.text("Bounties"), 6);
        for (int i = 0; i < Math.min(45, list.size()); i++) {
            Map.Entry<UUID, Double> e = list.get(i);
            m.set(i, head(Bukkit.getOfflinePlayer(e.getKey()), "&c" + name(e.getKey()),
                    List.of("&7Bounty: &a" + fmt(e.getValue()), "&7Kill them to claim it.")), null);
        }
        m.set(49, icon(Material.PAPER, "&eHow to place a bounty",
                List.of("&f/bounty add <player> <amount>", "&7Minimum: &a" + fmt(getConfig().getDouble("bounty.min", 100)))), null);
        p.openInventory(m.inv);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        deaths.merge(victim.getUniqueId(), 1, Integer::sum);
        dirty = true;
        if (killer == null || killer.equals(victim)) return;
        kills.merge(killer.getUniqueId(), 1, Integer::sum);
        Double reward = bounties.remove(victim.getUniqueId());
        if (reward != null && reward > 0) {
            add(killer.getUniqueId(), reward);
            Bukkit.broadcast(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&6&lBOUNTY &f" + killer.getName() + " &ekilled &c" + victim.getName()
                            + " &eand claimed the &a" + fmt(reward) + " &ebounty!"));
        }
    }

    // ---------------- /stats ----------------

    private enum Stat {
        MONEY("Money", Material.GOLD_INGOT), SHARDS("Shards", Material.AMETHYST_SHARD),
        KILLS("Kills", Material.DIAMOND_SWORD), DEATHS("Deaths", Material.SKELETON_SKULL),
        PLAYTIME("Playtime", Material.CLOCK), POINTS("Points", Material.NETHER_STAR);
        final String label;
        final Material icon;
        Stat(String label, Material icon) { this.label = label; this.icon = icon; }
    }

    private double statValue(Stat st, UUID u) {
        return switch (st) {
            case MONEY -> bal(u);
            case SHARDS -> shards(u);
            case KILLS -> kills.getOrDefault(u, 0);
            case DEATHS -> deaths.getOrDefault(u, 0);
            case PLAYTIME -> playMinutes.getOrDefault(u, 0L);
            case POINTS -> points.getOrDefault(u, 0);
        };
    }

    private String fmtStat(Stat st, double v) {
        return switch (st) {
            case MONEY -> fmt(v);
            case PLAYTIME -> ((long) v / 60) + "h " + ((long) v % 60) + "m";
            default -> String.valueOf((long) v);
        };
    }

    private void playtimeTick() {
        int perMinute = getConfig().getInt("points.per-minute", 1);
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            playMinutes.merge(id, 1L, Long::sum);
            int mult = boostUntil.getOrDefault(id, 0L) > now ? boostMult.getOrDefault(id, 1) : 1;
            if (perMinute > 0) points.merge(id, perMinute * mult, Integer::sum);
        }
        dirty = true;
    }

    private void openStats(Player p) {
        AMenu m = new AMenu(Component.text("Leaderboards"), 3);
        int slot = 10;
        for (Stat st : Stat.values()) {
            m.set(slot++, icon(st.icon, "&e" + st.label, List.of("&7Click to see the top 10")), ct -> openLeaderboard(p, st));
        }
        UUID id = p.getUniqueId();
        m.set(22, head(p, "&b" + p.getName(), List.of(
                "&7Money: &f" + fmt(bal(id)),
                "&7Shards: &f" + shards(id),
                "&7Points: &f" + points.getOrDefault(id, 0),
                "&7Kills: &f" + kills.getOrDefault(id, 0),
                "&7Deaths: &f" + deaths.getOrDefault(id, 0),
                "&7Playtime: &f" + fmtStat(Stat.PLAYTIME, playMinutes.getOrDefault(id, 0L)))), null);
        p.openInventory(m.inv);
    }

    private void openLeaderboard(Player p, Stat st) {
        List<UUID> ids = new ArrayList<>(names.keySet());
        ids.sort((x, y) -> Double.compare(statValue(st, y), statValue(st, x)));
        AMenu m = new AMenu(Component.text("Top " + st.label), 3);
        int shown = 0;
        for (UUID u : ids) {
            if (shown >= 10) break;
            double v = statValue(st, u);
            if (v <= 0) break;
            m.set(shown, head(Bukkit.getOfflinePlayer(u), "&e#" + (shown + 1) + " &f" + name(u),
                    List.of("&7" + st.label + ": &a" + fmtStat(st, v))), null);
            shown++;
        }
        m.set(22, icon(Material.ARROW, "&7Back", null), ct -> openStats(p));
        p.openInventory(m.inv);
    }

    // ---------------- top lists + PlaceholderAPI ----------------

    private void refreshTops() {
        Map<Stat, List<String[]>> out = new HashMap<>();
        for (Stat st : Stat.values()) {
            List<UUID> ids = new ArrayList<>(names.keySet());
            ids.sort((x, y) -> Double.compare(statValue(st, y), statValue(st, x)));
            List<String[]> rows = new ArrayList<>();
            for (UUID u : ids) {
                if (rows.size() >= 10) break;
                double v = statValue(st, u);
                if (v <= 0) break;
                rows.add(new String[]{name(u), fmtStat(st, v)});
            }
            out.put(st, rows);
        }
        topRows = out;
    }

    private static Stat statByKey(String key) {
        for (Stat st : Stat.values()) {
            if (st.name().equalsIgnoreCase(key)) return st;
        }
        return null;
    }

    /** Resolves %goldrush_<params>%. Returns null for unknown placeholders. */
    public String placeholder(OfflinePlayer player, String params) {
        String[] parts = params.toLowerCase(Locale.ROOT).split("_");
        Stat st = statByKey(parts[0]);
        if (st != null && parts.length >= 3 && parts[1].equals("top")) {
            int n;
            try { n = Integer.parseInt(parts[2]); } catch (NumberFormatException ex) { return null; }
            List<String[]> rows = topRows.getOrDefault(st, List.of());
            String suffix = parts.length > 3 ? parts[3] : "";
            if (n < 1 || n > rows.size()) return suffix.equals("value") ? "0" : "-";
            String[] row = rows.get(n - 1);
            return switch (suffix) {
                case "name" -> row[0];
                case "value" -> row[1];
                default -> row[0] + " - " + row[1];
            };
        }
        if (player == null) return "";
        UUID u = player.getUniqueId();
        if (st != null && parts.length == 1) return fmtStat(st, statValue(st, u));
        if (st != null && parts.length == 2 && parts[1].equals("raw")) return String.valueOf((long) statValue(st, u));
        if (parts[0].equals("bounty") && parts.length == 1) return fmt(bounties.getOrDefault(u, 0.0));
        if (parts[0].equals("booster") && parts.length == 1) {
            return boostUntil.getOrDefault(u, 0L) > System.currentTimeMillis() ? boostMult.getOrDefault(u, 1) + "x" : "none";
        }
        return null;
    }

    /** The %goldrush_...% placeholders. Only loaded when PlaceholderAPI is installed. */
    private static final class GoldrushExpansion extends PlaceholderExpansion {
        private final ShopPlugin plugin;

        private GoldrushExpansion(ShopPlugin plugin) { this.plugin = plugin; }

        static void install(ShopPlugin plugin) { new GoldrushExpansion(plugin).register(); }

        @Override public String getIdentifier() { return "goldrush"; }
        @Override public String getAuthor() { return "Goldrush"; }
        @Override public String getVersion() { return "1.0"; }
        @Override public boolean persist() { return true; }

        @Override
        public String onRequest(OfflinePlayer player, String params) {
            return plugin.placeholder(player, params);
        }
    }

    // ---------------- points + boosters ----------------

    private String boosterText(UUID id) {
        long left = boostUntil.getOrDefault(id, 0L) - System.currentTimeMillis();
        if (left <= 0) return "&7No booster active";
        return "&e" + boostMult.getOrDefault(id, 1) + "x booster: &f" + Math.max(1, left / 60000) + " min left";
    }

    private boolean boosterAllowed(Player p, int mult) {
        UUID id = p.getUniqueId();
        Long until = boostUntil.get(id);
        int cur = boostMult.getOrDefault(id, 1);
        if (until != null && until > System.currentTimeMillis() && cur > mult) {
            msg(p, "&cYou already have a stronger booster (" + cur + "x) running.");
            return false;
        }
        return true;
    }

    private void applyBooster(Player p, int mult, int minutes) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long until = boostUntil.get(id);
        boolean active = until != null && until > now;
        long base = active && boostMult.getOrDefault(id, 1) == mult ? until : now;   // same booster: extend it
        boostUntil.put(id, base + minutes * 60_000L);
        boostMult.put(id, mult);
        dirty = true;
    }

    private void cmdPoints(CommandSender s, String[] a) {
        if (a.length >= 3 && s.hasPermission("shop.admin")) {
            UUID u = find(a[1]);
            if (u == null) { msg(s, "&cUnknown player (they must have joined once)."); return; }
            int amt;
            try { amt = Integer.parseInt(a[2]); } catch (NumberFormatException ex) { msg(s, "&cInvalid amount."); return; }
            if (amt < 0) { msg(s, "&cInvalid amount."); return; }
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "give" -> points.merge(u, amt, Integer::sum);
                case "take" -> points.put(u, Math.max(0, points.getOrDefault(u, 0) - amt));
                case "set" -> points.put(u, amt);
                default -> { msg(s, "&cUsage: /points <give|take|set> <player> <amount>"); return; }
            }
            dirty = true;
            msg(s, "&b" + name(u) + " now has &f" + points.getOrDefault(u, 0) + " &bpoints");
            return;
        }
        if (!(s instanceof Player p)) { msg(s, "&cUsage: /points <give|take|set> <player> <amount>"); return; }
        msg(p, "&bPoints: &f" + points.getOrDefault(p.getUniqueId(), 0) + " &7| " + boosterText(p.getUniqueId()));
        msg(p, "&7Spend them with &f/pointshop&7.");
    }

    // ---------------- /shop and /pointshop ----------------

    private record ShopEntry(Material mat, double price) { }

    private static final class ShopCat {
        final String id;
        String name = "";
        String title = "";
        String description = "";
        Material icon = Material.CHEST;
        int slot;
        boolean points;
        List<String> info = new ArrayList<>();
        final List<ShopEntry> items = new ArrayList<>();
        ShopCat(String id) { this.id = id; }
    }

    private record PointItem(String name, Material icon, int cost, Material item, int amount, String spawner,
                             List<String> commands, List<String> lore, int boostMult, int boostMinutes) { }

    private static final class PointCat {
        final String id;
        String name = "";
        String title = "";
        String description = "";
        Material icon = Material.CHEST;
        int slot;
        List<String> info = new ArrayList<>();
        final List<PointItem> items = new ArrayList<>();
        PointCat(String id) { this.id = id; }
    }

    private void loadShops() {
        shopCats.clear();
        pointCats.clear();
        double markup = getConfig().getDouble("shop.min-markup", 1.1);

        ConfigurationSection sc = getConfig().getConfigurationSection("shop");
        if (sc == null) sc = new YamlConfiguration();
        shopTitle = sc.getString("title", "&8\u25BA Shop &8| Menu");
        shopRows = Math.max(1, Math.min(6, sc.getInt("rows", 3)));
        ConfigurationSection cats = sc.getConfigurationSection("categories");
        if (cats != null) {
            for (String id : cats.getKeys(false)) {
                ConfigurationSection c = cats.getConfigurationSection(id);
                if (c == null) continue;
                ShopCat cat = new ShopCat(id);
                cat.name = c.getString("name", "&f" + id);
                cat.title = c.getString("title", "&8\u25BA " + id + " &8| Menu");
                cat.description = c.getString("description", "");
                Material ic = Material.matchMaterial(c.getString("icon", "CHEST"));
                if (ic != null && ic.isItem()) cat.icon = ic;
                cat.slot = c.getInt("slot", 0);
                cat.points = c.getBoolean("points", false);
                cat.info = c.getStringList("info");
                ConfigurationSection itemSec = c.getConfigurationSection("items");
                if (itemSec != null) {
                    for (String k : itemSec.getKeys(false)) {
                        Material m = Material.matchMaterial(k);
                        if (m == null || !m.isItem() || m.isAir()) {
                            getLogger().warning("config.yml (shop): unknown item " + k);
                            continue;
                        }
                        double price = itemSec.getDouble(k);
                        Double sell = prices.get(m);
                        if (sell != null && price < sell * markup) {
                            double fixed = round(sell * markup);
                            getLogger().warning("config.yml (shop): " + k + " costs $" + price + " but sells for $" + sell
                                    + "; raised to $" + fixed + " so nobody can buy and re-sell it for profit.");
                            price = fixed;
                        }
                        if (price > 0) cat.items.add(new ShopEntry(m, price));
                    }
                }
                shopCats.put(id, cat);
            }
        }

        ConfigurationSection pc = getConfig().getConfigurationSection("pointshop");
        if (pc == null) pc = new YamlConfiguration();
        pointTitle = pc.getString("title", "&8\u25BA Point Shop &8| Menu");
        pointRows = Math.max(1, Math.min(6, pc.getInt("rows", 3)));
        ConfigurationSection pcats = pc.getConfigurationSection("categories");
        if (pcats != null) {
            for (String id : pcats.getKeys(false)) {
                ConfigurationSection c = pcats.getConfigurationSection(id);
                if (c == null) continue;
                PointCat cat = new PointCat(id);
                cat.name = c.getString("name", "&f" + id);
                cat.title = c.getString("title", "&8\u25BA " + id + " &8| Menu");
                cat.description = c.getString("description", "");
                Material ic = Material.matchMaterial(c.getString("icon", "CHEST"));
                if (ic != null && ic.isItem()) cat.icon = ic;
                cat.slot = c.getInt("slot", 0);
                cat.info = c.getStringList("info");
                for (Map<?, ?> m : c.getMapList("items")) {
                    Material icon = Material.matchMaterial(str(m, "icon", "PAPER"));
                    if (icon == null || !icon.isItem() || icon.isAir()) icon = Material.PAPER;
                    Material item = null;
                    if (m.get("item") != null) {
                        item = Material.matchMaterial(str(m, "item", ""));
                        if (item == null || !item.isItem() || item.isAir()) {
                            getLogger().warning("config.yml (pointshop): unknown item " + m.get("item"));
                            continue;
                        }
                    }
                    String spawner = null;
                    if (m.get("spawner") != null) {
                        spawner = String.valueOf(m.get("spawner")).toUpperCase(Locale.ROOT);
                        try {
                            EntityType.valueOf(spawner);
                        } catch (IllegalArgumentException ex) {
                            getLogger().warning("config.yml (pointshop): unknown mob " + m.get("spawner"));
                            continue;
                        }
                        if (m.get("icon") == null) icon = Material.SPAWNER;
                    }
                    List<String> cmds = new ArrayList<>();
                    if (m.get("commands") instanceof List<?> l) for (Object o : l) cmds.add(String.valueOf(o));
                    List<String> lore = new ArrayList<>();
                    if (m.get("lore") instanceof List<?> l) for (Object o : l) lore.add(String.valueOf(o));
                    int boostMultiplier = 0;
                    int boostMinutes = 0;
                    if (m.get("booster") instanceof Map<?, ?> bm) {
                        boostMultiplier = Math.max(2, num(bm, "multiplier", 2));
                        boostMinutes = Math.max(1, num(bm, "minutes", 30));
                    }
                    if (item == null && spawner == null && cmds.isEmpty() && boostMultiplier <= 0) {
                        getLogger().warning("config.yml (pointshop): an entry in " + id + " does nothing, skipped");
                        continue;
                    }
                    cat.items.add(new PointItem(str(m, "name", "&fItem"), icon, Math.max(0, num(m, "cost", 1)), item,
                            Math.max(1, Math.min(64, num(m, "amount", 1))), spawner, cmds, lore,
                            boostMultiplier, boostMinutes));
                }
                pointCats.put(id, cat);
            }
        }
        getLogger().info("Loaded " + shopCats.size() + " shop and " + pointCats.size() + " point shop categories.");
    }

    private static ItemStack catIcon(Material icon, String name, String description, List<String> info) {
        List<String> lore = new ArrayList<>();
        if (!description.isBlank()) lore.add("&7" + description);
        lore.add("");
        lore.add("&bInformation:");
        for (String l : info) lore.add("&f" + l);
        lore.add("");
        lore.add("&e\u25BA &e&nCLICK&r&e to open");
        return icon(icon, name, lore);
    }

    private void openShop(Player p) {
        AMenu m = new AMenu(LegacyComponentSerializer.legacyAmpersand().deserialize(shopTitle), shopRows);
        for (ShopCat cat : shopCats.values()) {
            if (cat.slot < 0 || cat.slot >= shopRows * 9) continue;
            m.set(cat.slot, catIcon(cat.icon, cat.name, cat.description, cat.info), ct -> {
                if (cat.points) openPointShop(p); else openShopCategory(p, cat);
            });
        }
        p.openInventory(m.inv);
    }

    private void openShopCategory(Player p, ShopCat cat) {
        int n = Math.min(45, cat.items.size());
        int rows = Math.min(6, Math.max(2, (n + 8) / 9 + 1));
        AMenu m = new AMenu(LegacyComponentSerializer.legacyAmpersand().deserialize(cat.title), rows);
        for (int i = 0; i < n; i++) {
            ShopEntry e = cat.items.get(i);
            int stack = e.mat().getMaxStackSize();
            ItemStack it = icon(e.mat(), "&f" + pretty(e.mat()), List.of(
                    "&7Price: &a" + fmt(e.price()) + " &7each", "",
                    "&eLeft-click: &fbuy 1",
                    "&eRight-click: &fbuy " + stack + " &7(" + fmt(round(e.price() * stack)) + ")"));
            m.set(i, it, ct -> buyShop(p, cat, e, ct.isRightClick() ? stack : 1));
        }
        m.set(rows * 9 - 5, icon(Material.ARROW, "&7Back", null), ct -> openShop(p));
        m.set(rows * 9 - 1, icon(Material.GOLD_INGOT, "&6Your balance: &f" + fmt(bal(p.getUniqueId())), null), null);
        p.openInventory(m.inv);
    }

    private void buyShop(Player p, ShopCat cat, ShopEntry e, int amount) {
        double cost = round(e.price() * amount);
        if (!take(p.getUniqueId(), cost)) { msg(p, "&cYou need " + fmt(cost) + "."); return; }
        giveMaterial(p, e.mat(), amount);
        msg(p, "&aBought &f" + amount + "x " + pretty(e.mat()) + " &afor &f" + fmt(cost) + "&a.");
        openShopCategory(p, cat);
    }

    private void openPointShop(Player p) {
        AMenu m = new AMenu(LegacyComponentSerializer.legacyAmpersand().deserialize(pointTitle), pointRows);
        for (PointCat cat : pointCats.values()) {
            if (cat.slot < 0 || cat.slot >= pointRows * 9) continue;
            m.set(cat.slot, catIcon(cat.icon, cat.name, cat.description, cat.info), ct -> openPointCategory(p, cat));
        }
        UUID id = p.getUniqueId();
        m.set(pointRows * 9 - 1, icon(Material.NETHER_STAR, "&bYour points: &f" + points.getOrDefault(id, 0),
                List.of(boosterText(id))), null);
        m.set(pointRows * 9 - 9, icon(Material.ARROW, "&7Back to the shop", null), ct -> openShop(p));
        p.openInventory(m.inv);
    }

    private void openPointCategory(Player p, PointCat cat) {
        int n = Math.min(45, cat.items.size());
        int rows = Math.min(6, Math.max(2, (n + 8) / 9 + 1));
        AMenu m = new AMenu(LegacyComponentSerializer.legacyAmpersand().deserialize(cat.title), rows);
        for (int i = 0; i < n; i++) {
            PointItem pi = cat.items.get(i);
            List<String> lore = new ArrayList<>(pi.lore());
            if (!lore.isEmpty()) lore.add("");
            lore.add("&7Cost: &b" + pi.cost() + " points");
            lore.add("&eClick to buy");
            m.set(i, icon(pi.icon(), pi.name(), lore), ct -> buyPoint(p, pi, cat));
        }
        UUID id = p.getUniqueId();
        m.set(rows * 9 - 5, icon(Material.ARROW, "&7Back", null), ct -> openPointShop(p));
        m.set(rows * 9 - 1, icon(Material.NETHER_STAR, "&bYour points: &f" + points.getOrDefault(id, 0),
                List.of(boosterText(id))), null);
        p.openInventory(m.inv);
    }

    private void buyPoint(Player p, PointItem pi, PointCat cat) {
        UUID id = p.getUniqueId();
        if (pi.boostMult() > 0 && !boosterAllowed(p, pi.boostMult())) return;
        int have = points.getOrDefault(id, 0);
        if (have < pi.cost()) {
            msg(p, "&cYou need &b" + pi.cost() + " &cpoints (you have " + have + ").");
            return;
        }
        points.put(id, have - pi.cost());
        dirty = true;
        if (pi.item() != null) giveMaterial(p, pi.item(), pi.amount());
        if (pi.spawner() != null) giveItem(p, spawnerItem(pi.spawner(), pi.amount()));
        for (String cmd : pi.commands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        }
        if (pi.boostMult() > 0) applyBooster(p, pi.boostMult(), pi.boostMinutes());
        msg(p, "&aPurchased &r" + pi.name() + " &afor &b" + pi.cost() + " &apoints.");
        openPointCategory(p, cat);
    }

    // ---------------- GUI plumbing ----------------

    private static void nav(Inventory inv, int page, int pages) {
        if (page > 0) inv.setItem(45, icon(Material.ARROW, "&ePrevious page", null));
        inv.setItem(49, icon(Material.PAPER, "&7Page " + (page + 1) + "/" + pages, null));
        if (page < pages - 1) inv.setItem(53, icon(Material.ARROW, "&eNext page", null));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Gui g)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;

        int slot = e.getSlot();
        boolean paged = g.type != Type.SHARDS;
        if (paged && (slot == 45 || slot == 53)) {
            int np = g.page + (slot == 53 ? 1 : -1);
            Bukkit.getScheduler().runTask(this, () -> reopen(p, g.type, np));
            return;
        }
        if (g.type == Type.AH && slot == 48) {
            ahSort.put(p.getUniqueId(), (ahSort.getOrDefault(p.getUniqueId(), 0) + 1) % 3);
            Bukkit.getScheduler().runTask(this, () -> reopen(p, g.type, 0));
            return;
        }
        if (slot < 0 || slot >= g.ids.length || g.ids[slot] < 0) return;
        int id = g.ids[slot];
        ClickType click = e.getClick();

        switch (g.type) {
            case AH -> clickAh(p, id, click);
            case ORDERS -> clickOrder(p, id, click);
            case SHARDS -> clickShard(p, id);
        }
        if (paged) Bukkit.getScheduler().runTask(this, () -> reopen(p, g.type, g.page));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Gui) e.setCancelled(true);
    }

    private void reopen(Player p, Type t, int page) {
        if (!p.isOnline()) return;
        switch (t) {
            case AH -> openAh(p, page);
            case ORDERS -> openOrders(p, page);
            case SHARDS -> openShards(p);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        names.put(p.getUniqueId(), p.getName());
        bal.putIfAbsent(p.getUniqueId(), (double) getConfig().getInt("economy.start-balance", 500));
        dirty = true;
    }
}
