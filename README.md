# RTPTrials

A Fabric mod for Minecraft 1.21.4 that periodically teleports players to random locations across the world, turning every session into a survival challenge. Land somewhere unknown, fight boosted mobs, and loot the chest that spawns on arrival — with a rare chance of striking it lucky with OP gear.

---

## Features

- **Periodic Random Teleports** — every few minutes all survival players are teleported to a random location in the world
- **10-Second Countdown** — a subtitle timer counts down before each teleport, turning red in the final 3 seconds with audio cues
- **Action Bar Reminders** — a small reminder flashes above the hotbar every 30 seconds showing how long until the next teleport
- **Smart Location Selection** — teleports prefer landing 500–1000 blocks from your current position, avoiding spawn, the world origin, and recently used locations
- **Loot Chest on Arrival** — a chest spawns beside you every teleport, filled with loot from a random vanilla loot table
- **OP Chest (1 in 35 chance)** — a rare trapped chest can spawn instead, filled with high-value loot such as elytra, netherite, enchanted books, and totems. A bold gold message announces your luck
- **Combat Delay** — if you are in combat when the teleport fires, it waits until you are out of combat before sending you
- **Boosted Mob Spawns** — hostile mob spawn caps are raised by 10 and passive mob caps by 2, making the world feel more alive and dangerous
- **Dimension Aware** — safe landing positions are found correctly in the Overworld, Nether, and End
- **Operator Commands** — full control over the mod at runtime without a restart

---

## Commands

All commands require operator permission level 2.

| Command | Description |
|---|---|
| `/rtpr toggle` | Enable or disable random teleporting |
| `/rtpr interval <minutes>` | Set how often teleports occur (1–60 minutes) |
| `/rtpr radius <blocks>` | Set the maximum teleport radius (500–100,000 blocks) |
| `/rtpr teleport <player>` | Manually trigger a teleport countdown for one or more players |

---

## Default Settings

| Setting | Default |
|---|---|
| Teleport interval | 5 minutes |
| Teleport radius | 100,000 blocks |
| Preferred landing distance | 500–1000 blocks from player |
| Minimum distance from origin | 250 blocks |
| OP chest chance | 1 in 20 |
| Monster spawn bonus | +10 |
| Passive spawn bonus | +2 |

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 1.21.4
2. Install the [Fabric API](https://modrinth.com/mod/fabric-api)
3. Drop `RTPTrials.jar` into your `mods` folder
4. Launch the game

---

## Compatibility

- **Minecraft:** 1.21.4
- **Fabric Loader:** 0.15.0 or higher
- **Fabric API:** required
- **Environment:** server-side (clients do not need the mod installed)

---

## License

MIT
