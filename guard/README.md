# Guard anti-cheat setup (GoldRush)

Guard is a lightweight, staff-first Paper anti-cheat. It combines buffered movement/combat checks, suspicious mining alerts, remote block-action heuristics, staff review tools, and optional player-entity culling.

## Compatibility

- Target API: Paper 1.21.11
- Java: 21
- The repository build workflow compiles Guard, Shop, and Core and uploads the three JARs as a GitHub Actions artifact.

## Safe defaults

Guard's embedded first-run defaults now keep automatic bans disabled:
- `detection.ban-enabled: false`
- `detection.keybind-check.ban: false`

Keep these disabled while testing. Client brand strings, plugin channels, and keybind responses are not definitive proof of cheating. Review alerts and evidence manually before punishing anyone. If you already have `plugins/Guard/config.yml`, changing the embedded defaults will not overwrite it; edit the existing file yourself.

## Freecam / remote block actions

The `anti-freecam` check flags repeated block breaks or right-click interactions when the block center is unusually far from the player's server-side eye position. It is a heuristic for remote interactions, **not** a way to see the player's camera or prove that a freecam mod is installed.

Default:
```yaml
anti-freecam:
  enabled: true
  max-block-interaction-distance: 6.0
```

Do not use this check alone for automatic punishment. Test with legitimate reach, lag, and custom server mechanics first.

## Entity culling / anti-ESP

Entity culling hides other player entities from a viewer when the target is occluded. It can reduce information exposed to some ESP/radar clients, but it does not hide blocks or chunk data and cannot guarantee prevention or detection of freecam. Staff with `anticheat.culling.bypass` can see all players.

## Paper anti-xray: protect players, bypass for trusted admins

Paper's built-in chunk obfuscation is the actual anti-xray layer. In `config/paper-world-defaults.yml`, preserve the existing block lists and set:

```yaml
anticheat:
  anti-xray:
    enabled: true
    engine-mode: 2
    use-permission: true
```

Check each world's `paper-world.yml` too: per-world values can override the defaults. Restart the server after editing.

With `use-permission: true`, grant Paper's bypass only to trusted admin groups using LuckPerms:

```text
/lp group admin permission set paper.antixray.bypass true
/lp group owner permission set paper.antixray.bypass true
```

Do **not** grant this permission to the default/player groups. Paper operators may also bypass anti-xray, so do not OP ordinary players. If your group names differ, substitute your actual staff group names.

Run `/acstatus` in-game to review Guard's anti-xray status. It reads Paper's defaults and loaded per-world configuration; restart after changes so the audit reflects the live configuration.

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
