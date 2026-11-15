# ✿ BlossomCore

The core plugin for Blossom SMP (Paper 1.21.11, Java 21): **free ranks, balanced kits, PvP rating and menus**, all sharing one player file.

This is **part 1** of the big Blossom SMP plan. Quests, events, custom items, custom biomes and cosmetics come in later parts and plug into this one.

## What it does

- **Free ranks** you earn by playing: Seedling → Sprout → Gardener → Bloom → Blossom → Sakura → Eternal. They can't be bought.
- **Blossom Points** for playing, mining, fighting monsters and winning PvP fights (standing AFK earns nothing, and mob farms are capped per day).
- **19 kits**: a Starter Kit for new players, 6 survival kits, 5 PvP kits (iron first, then diamond) and 7 VIP kits (diamond and netherite).
- **Kits reset when you die** (every kit, VIP too). Kit gear stays locked to you and disappears when you die, so it can't be farmed.
- **Kit limits that can't be broken by a config mistake**: enchantment caps, golden apple caps, banned items.
- **PvP rating** with 8 PvP ranks (Rookie → Blossom Legend), streaks, a top 10 and seasons. Separate from the free ranks.
- **Anti-abuse**: alt accounts, kill trading, teammates and killing the same player over and over don't give rating.
- **Sword-and-axe PvP only**: end crystals, respawn anchors and maces don't hurt players.
- **Menus** for everything: `/blossom`, `/ranks`, `/kits`, `/pvp`, `/pvptop`, `/profile`.
- Placeholders for TAB and holograms.

---

## ⚠️ Read this first: 3 things on your server that clash

**1. `kits.sk` (your Skript kits).** It also uses `/kit` and `/kits`, and it also gives a starter kit. Delete `plugins/Skript/scripts/kits.sk` (keep a copy somewhere) and type `sk reload scripts`. Your old kit permissions (`blossom.kit.vip` and so on) are not used any more.

**2. BlossomRanks (kill ranks).** It still works next to BlossomCore, but its rank names (Seedling, Sprout, Blossom...) are the same words as the new free ranks, so players will see two different "Seedling" ranks. The new PvP ranks do the same job with rating instead of kills. I'd remove BlossomRanks. Keep it if you prefer; nothing breaks.

**3. Petals.** Petal rewards run the command `petals give <player> <amount>`, which needs **BlossomToggles 1.1.0** (Petals moved there from BlossomSettings).

RankedSMP (the #1–#20 "steal a spot" ranking), BlossomEconomy, BlossomCombat and BlossomTeams all keep working as they are.

---

## Build the .jar (GitHub, no installing)

1. Make a new repository on GitHub called **BlossomCore**.
2. Unzip `BlossomCore.zip`. Upload **everything inside the BlossomCore folder**, including the `.github` folder. `pom.xml` and `src` must be on the main page of the repo, not inside another folder.
3. Open the **Actions** tab and wait for the green tick.
4. Click the finished run, scroll down and download **BlossomCore** (a zip with `BlossomCore.jar` inside).

If you get a red cross instead, send me a screenshot of the error.

## Install

1. Stop the server.
2. Delete `kits.sk` (see above).
3. Upload `BlossomCore.jar` to `plugins` and start the server.
4. The console should say:
   - `Hooked into Vault (money rewards and rank costs are on).`
   - `Hooked into PlaceholderAPI.`
   - `BlossomCore is ready: 7 ranks, 19 kits, 8 PvP ranks. PvP season 1.`
5. Type `/blossom` in game.

Players who were already on your server do **not** get a second Starter Kit. Only brand new players do.

The files are in `plugins/BlossomCore`:

| File | What's in it |
|---|---|
| `config.yml` | Kit limits, golden apple limits, menu look, sounds, Petals command |
| `ranks.yml` | The free ranks and how Blossom Points are earned |
| `kits.yml` | Every kit |
| `pvp.yml` | Rating, PvP ranks, anti-abuse, banned PvP styles |
| `messages.yml` | Every message |
| `players/` | One file per player. Don't edit these while the player is online. |

After editing a file, type `/blossomcore reload`. If you made a mistake, it tells you which file, keeps your old settings running, and the console says which kit or line is wrong.

---

## Set up the VIP ranks (LuckPerms)

Each VIP rank is one permission. A higher rank includes the lower ones, so each group needs only one line. Run these in the console, one at a time:

```
lp creategroup vip
lp creategroup vipplus
lp creategroup elite
lp creategroup legend
lp group vip permission set blossomcore.vip true
lp group vipplus permission set blossomcore.vipplus true
lp group elite permission set blossomcore.elite true
lp group legend permission set blossomcore.legend true
```

(You already have a `vip` group, so `lp creategroup vip` will just say it exists.)

Give someone a rank with `lp user <name> parent add elite`.

## Show the ranks in TAB

In `plugins/TAB/groups.yml`, add the placeholders where you want them, for example:

```yaml
_DEFAULT_:
  tabprefix: "%blossomcore_rank_prefix% &8| &f"
  tabsuffix: " &8[%blossomcore_pvp_rank%&8]"
```

Then `tab reload`. For the scoreboard, a line like `'&#F4A9C0✿ &7Rating: &f%blossomcore_pvp_rating%'` works the same way.

---

## Commands

| Command | What it does |
|---|---|
| `/blossom` (`/menu`) | The main menu |
| `/blossom help` | The command list |
| `/ranks` | Free ranks and your progress |
| `/rankup` | Rank up when you have finished the requirements |
| `/kits` | The kit menu (left-click = claim, right-click = look inside) |
| `/kit <name>` | Claim a kit |
| `/kit preview <name>` | Look inside a kit |
| `/pvp` | Your PvP rating and the PvP ranks |
| `/pvptop` | The 10 best PvP players |
| `/profile [player]` | A profile (the player must be online) |

Staff (permission `blossomcore.admin`, ops have it). These also work in the console without the slash. The player must be online.

| Command | What it does |
|---|---|
| `/blossomcore reload` | Read the files again |
| `/blossomcore points <give\|take\|set> <player> <amount>` | Change Blossom Points |
| `/blossomcore rank set <player> <rank>` | Put a player on a free rank (no rewards are given) |
| `/blossomcore rating set <player> <amount>` | Change a PvP rating |
| `/blossomcore kit give <player> <kit>` | Give a kit with no cooldown |
| `/blossomcore kit reset <player> [kit]` | Clear kit cooldowns |
| `/blossomcore season end confirm` | End the PvP season |
| `/blossomcore save` | Save everyone now |

---

## The free ranks (ranks.yml)

A player needs **everything** in the row, then types `/rankup`. The money is paid when they rank up; Blossom Points are not spent.

| Rank | Blossom Points | Hours played | Also | Cost | Unlocks | Reward |
|---|---|---|---|---|---|---|
| Seedling | - | - | - | - | Starter Kit, Survival Kit | - |
| Sprout | 100 | 1 | - | $500 | Miner, Farmer, Iron Fighter | 100 Petals |
| Gardener | 400 | 4 | 500 blocks mined | $2,500 | Explorer, Builder, Advanced Iron | 250 Petals |
| Bloom | 1,000 | 10 | 150 mobs | $7,500 | Diamond Archer | 500 Petals |
| Blossom | 2,500 | 25 | 5,000 blocks mined | $20,000 | Diamond Fighter | 1,000 Petals |
| Sakura | 5,000 | 60 | 1,000 mobs | $50,000 | Diamond Warrior | 2,000 Petals |
| Eternal | 10,000 | 120 | - | $125,000 | Eternal Garden Kit | 5,000 Petals |

How Blossom Points are earned: 5 for every 10 minutes of playing, 1 per monster, 1 per 25 blocks mined (monsters and mining together stop at 250 a day), and 20 per counted PvP kill. Staff can give points too, and quests and events will give them when those parts are built.

**These numbers are my starting guess.** You know your players, so change them in `ranks.yml` if the ladder feels too fast or too slow.

## The kits (kits.yml)

| Kit | Who | Cooldown | Golden apples |
|---|---|---|---|
| Starter | every new player, automatically | once | 0 |
| Survival | Seedling | 24h | 0 |
| Miner, Farmer | Sprout | 24h | 0 |
| Explorer, Builder | Gardener | 2 days | 0 |
| Eternal Garden | Eternal | 7 days | 0 |
| Iron Fighter | Sprout | 12h | 1 |
| Advanced Iron (Sharpness I, Protection I) | Gardener | 24h | 2 |
| Diamond Archer (Power II bow) | Bloom | 2 days | 2 |
| Diamond Fighter | Blossom | 3 days | 4 |
| Diamond Warrior (Sharpness I, Protection I) | Sakura | 3 days | 5 |
| VIP (plain diamond) | VIP | 2 days | 5 |
| VIP+ (Sharpness I, Protection I) | VIP+ | 2 days | 5 |
| Elite (Sharpness II, Protection II) | Elite | 3 days | 6 |
| Blossom Legend (Sharpness III, Protection III, named gear) | Blossom Legend | 3 days | 6 |
| VIP Netherite (plain) | VIP+ **and** Gardener | 7 days | 4 |
| Elite Netherite (Sharpness II, Protection II) | Elite **and** Gardener | 7 days | 5 |
| Legend Netherite (Sharpness III, Protection III) | Blossom Legend **and** Gardener | 10 days | 6 |

The cooldowns above are for players who stay alive. **Dying resets every kit**, so after a death you can claim them all again straight away.

### Kits reset when you die (new in 1.1)

When a player dies, every kit they've unlocked can be claimed again, VIP and Netherite kits too, and they're told so when they respawn. To stop that becoming an endless gear machine, **kit gear stays with the player who claimed it**:

- It can't be put in chests, ender chests, shulkers, barrels, hoppers, bundles, item frames, armour stands or decorated pots, and it can't be dropped. Crafting tables, anvils, smithing tables, enchanting tables and grindstones still work.
- It **disappears when its owner dies**, so killers don't loot it and graves or death chests never see it. This works with keepInventory on too.
- Golden apples and ender pearls from kits count as kit gear. Normal golden apples you find or craft are untouched.
- The **12 golden apples a day** limit still applies, so dying over and over doesn't give endless apples.
- You can re-kit straight after dying (the 20 second "just fought" wait is cleared for the player who died).

Settings, in `config.yml` under `kits:`:

| Setting | What it does |
|---|---|
| `death-reset.enabled` | `false` = kits never reset on death |
| `death-reset.all-kits` | `false` = only kits with `reset-on-death: true` in `kits.yml` reset |
| `kit-gear.locked` | Kit gear can't be stored or dropped |
| `kit-gear.vanish-on-death` | Kit gear is deleted on death |
| `kit-gear.also-mark` | Stackable items that also count as kit gear |

A single kit can opt out by adding `reset-on-death: false` to it in `kits.yml`. If you turn off `locked` or `vanish-on-death` while death reset is on, the console warns you, because players could then stack up endless kit gear.

### The limits that keep it fair (config.yml)

These always win over `kits.yml`, so a typo can't make an overpowered kit:

- **Enchantments** stop at level 3 (Knockback, Punch, Fire Aspect and Flame at 1; Infinity, Thorns and Mending are off).
- **Golden apples**: at most 6 in one kit, and at most **12 a day from all kits together**. After that a kit still works, just without the apples.
- **Never in a kit**: end crystals, respawn anchors, maces, enchanted golden apples, totems.
- **No kits in a fight**: a kit can't be claimed for 20 seconds after hitting or being hit by a player.
- **No wasted kits**: if the inventory is too full, the kit is not claimed and no cooldown starts.
- A claim is saved to disk right away, before the items are handed over, so a crash or a quick re-log can't give a kit twice.
- Weapons, tools and armour from kits get a small "Kit item" line.

Free players get diamond through ranks, and everyone can still enchant their own gear in survival, so a VIP kit is a head start, not a ceiling.

---

## PvP (pvp.yml)

Everyone starts at **1000** rating. A kill on an equal player is worth **+16** and costs the loser **12**. Beating a stronger player pays more (up to 40), beating a much weaker one pays little (at least 4).

| PvP rank | Rating | First time each season | End of season |
|---|---|---|---|
| Rookie | 0 | - | - |
| Fighter | 1050 | 50 Petals | 100 Petals |
| Warrior | 1150 | 100 | 250 |
| Elite | 1300 | 200 | 500 |
| Champion | 1450 | 350 | 900 |
| Master | 1600 | 500 | 1,500 |
| Grandmaster | 1800 | 750 | 2,500 |
| Blossom Legend | 2000 | 1,000 | 4,000 |

**A kill does not count** (no rating, kills, deaths or streak change, and the killer is told why) when:

- the two players are on the **same internet connection** (alt accounts),
- they are on the **same team** (BlossomTeams),
- the victim has played **less than 1 hour** on the server,
- the victim was wearing **fewer than 2 armour pieces**,
- the killer already got a counted kill on that player in the last **30 minutes**,
- the two of them already have **3 counted kills between them today** (both directions together).

**Seasons.** When you want a new season, type `/blossomcore season end confirm`. Everyone gets the end-of-season reward for the rank they finished on (offline players get it when they next join), and ratings are pulled halfway back to 1000.

**Banned PvP styles** (each can be switched back on in `pvp.yml`): end crystals do no damage to players, respawn anchors can't be used outside the Nether, maces do no damage to players.

---

## Placeholders

| Placeholder | Example |
|---|---|
| `%blossomcore_rank%` | `✿ Blossom` (with colours) |
| `%blossomcore_rank_prefix%` | `Blossom` (short, for the tab list) |
| `%blossomcore_next_rank%` | `❀ Sakura` |
| `%blossomcore_points%` | `2,740` |
| `%blossomcore_pvp_rank%` | `Warrior` (with colours) |
| `%blossomcore_pvp_rating%` | `1184` |
| `%blossomcore_pvp_kills%` / `_deaths%` / `_kdr%` | `23` / `17` / `1.35` |
| `%blossomcore_pvp_streak%` / `%blossomcore_pvp_best_streak%` | `3` / `9` |
| `%blossomcore_pvp_position%` | `4` |
| `%blossomcore_season%` | `1` |
| `%blossomcore_top_1_name%`, `_rating%`, `_kills%`, `_rank%` (1 to 10) | for a hologram |

**Top PvP hologram:**
```
/dh create toppvp &#F4A9C0&l✿ TOP PVP ✿
/dh line add toppvp 1 &#FFD98A#1 &f%blossomcore_top_1_name% &8- &f%blossomcore_top_1_rating%
/dh line add toppvp 1 &7#2 &f%blossomcore_top_2_name% &8- &f%blossomcore_top_2_rating%
/dh line add toppvp 1 &7#3 &f%blossomcore_top_3_name% &8- &f%blossomcore_top_3_rating%
```

---

## Good to know (the honest limits)

- **It has not run on a real server yet.** I couldn't download Paper here, so the code was checked against stand-ins for the Paper classes, and the rating, anti-abuse and cooldown maths were tested on their own. The first build on GitHub and the first start on your server are the real test. Send me any red text.
- **Kit gear and the economy.** The `/sell` chest already refuses kit gear (it's a chest). `/sell hand` and `/ah sell` still need a check in BlossomEconomy so they refuse kit gear; that comes with the economy update.
- **Selling VIP kits for real money.** Minecraft's rules for servers don't allow selling gear that gives an advantage in fights. Giving VIP by hand is fine. If you sell it in a store, that is your decision to make, and the limits above keep the gap small.
- **Same-connection check.** Two real people in one house can't win rating from each other. Turn off `block-same-ip` in `pvp.yml` if that's a problem.
- **Staff commands need the player to be online.**
- **The leaderboard** refreshes every 30 seconds and leaves out players who never fought.
- **Not in this part:** quests, events, custom items, custom biomes, cosmetics, titles, auction house. Daily rewards, the shop and money stay in BlossomEconomy; Petals, `/ps` and spawners are in BlossomToggles.

## For developers

- Source: `src/main/java/com/blossomsmp/core/`. `rank/`, `kit/`, `pvp/` hold the systems, `menu/` the chest GUIs, `data/` the storage, `hook/` Vault and PlaceholderAPI (only loaded when those plugins are present).
- **Storage** (`DataStore`): one YAML file per player. All reads and writes go through a single background thread in order, files are written to `.tmp` and swapped in atomically, and a file that fails to parse blocks that login instead of being overwritten with empty data.
- **Pure logic** with no Bukkit imports: `RatingMath`, `KillGuard`, `Durations`.
- **Extending:** quests and events only need `ranks().addPoints(data, amount, false)`, `rewards().give(player, section)` and the `Kit`/`Rank` lookups; the same `rewards:` block (money, petals, points, commands) is used by ranks, PvP ranks and seasons.
- Build: `mvn package` (JDK 21) → `target/BlossomCore.jar`.
