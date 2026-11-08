# Guard anti-cheat setup (GoldRush)

Guard is a lightweight, staff-first Paper anti-cheat. It combines buffered movement/combat checks, suspicious mining alerts, remote block-action heuristics, staff review tools, and optional player-entity culling.

## Compatibility

- Target API: Paper 1.21.11
- Java: 21
- The repository build workflow compiles Guard, Shop, and Core and uploads the three JARs as a GitHub Actions artifact.

## Freecam protection (client-independent)

The goal is to protect the server against **all** Freecam clients (Anubis, Meteor, Wurst, Aristois, and custom clients), without relying on client names or brand strings.

A Paper plugin cannot see or disable a modified client's local camera. It cannot force every Freecam screen to turn black. The reliable server-side defense is to stop sending real hidden ore data to ordinary players. Paper's built-in anti-xray obfuscates chunk data for every client, regardless of which Freecam mod they use.

### 1. Enable Paper anti-xray

Merge the following section into `config/paper-world-defaults.yml` under the existing `anticheat:` key. Do not replace the whole file or delete existing settings.

```yaml
anticheat:
  anti-xray:
    enabled: true
    engine-mode: 2
    use-permission: true
```

A ready-to-merge example is in [paper-anti-xray.yml.example](paper-anti-xray.yml.example).

- `engine-mode: 2` uses obfuscation designed to make ore scouting through walls harder.
- `use-permission: true` makes `paper.antixray.bypass` the explicit bypass for trusted staff; ordinary players remain protected.
- Paper's default hidden-block list contains common ores. Review it and add any custom valuable blocks your server uses.
- Check each world's `paper-world.yml` too. Per-world settings can override the defaults.
- Restart the server after changing Paper configuration.

Grant bypass only to trusted staff groups (replace group names if yours differ):

```text
/lp group admin permission set paper.antixray.bypass true
/lp group owner permission set paper.antixray.bypass true
```

Do not grant this permission to default/player groups. Do not OP ordinary players: operators may have elevated access depending on server configuration.

### 2. Keep Guard's remote-action heuristic enabled

Guard also flags repeated block breaks or right-click interactions when the block center is unusually far from the player's server-side eye position.

Default in `plugins/Guard/config.yml`:

```yaml
anti-freecam:
  enabled: true
  max-block-interaction-distance: 6.0
```

This is supplementary detection only. It does not identify camera-only Freecam, and normal Paper reach validation may reject illegal interactions before a Bukkit event reaches Guard. Do not use this heuristic alone for automatic punishment.

### 3. Understand the limits

- Anti-xray protects hidden blocks/ores in chunk data; it does not make the entire screen black.
- It cannot hide blocks that are already legitimately visible, or information a player has already received.
- Entity culling hides occluded **player entities only**. It does not hide terrain, ores, or chunks.
- Client brand/keybind checks are not reliable proof: clients can disguise or omit these signals.
- Keep automatic bans disabled and review alerts manually.

## Entity culling / anti-ESP

Entity culling hides other player entities from a viewer when the target is occluded. It can reduce information exposed to some ESP/radar clients, but it does not hide blocks or chunk data. Staff with `anticheat.culling.bypass` can see all players.

## Staff commands

- `/sus list` — recent suspicious players
- `/sus chunks` — suspicious chunks from recent flags
- `/sus scan <player>` — manual client/keybind scan (never treat as conclusive proof)
- `/sus <player>` — inspect a player's flags
- `/sus tp <player>` — teleport to a player
- `/sus clear <player>` — clear stored flags
- `/freeze <player>` — freeze/unfreeze for staff review
- `/alerts` — toggle personal alerts
- `/acstatus` — Guard and Paper anti-xray status

Permissions are declared in `guard/src/main/resources/plugin.yml`; use LuckPerms to grant staff access as appropriate.
