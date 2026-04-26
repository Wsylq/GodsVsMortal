# Gods vs Mortals

A 3-day Minecraft server event where players vote to elect gods, build shrines, generate faith, and fight for dominance. Runs on Paper 1.21.x.

---

## How it works

The event runs over 3 in-game days. At the start, players vote to elect 3 gods. Everyone else becomes a mortal. Mortals build shrines dedicated to a god, which passively generate faith. Gods spend that faith on blessings and curses. On Day 3, Ragnarok kicks in and gods lose their damage immunity — mortals can finally kill them.

**Phases:** Voting → Day 1 → Day 2 → Day 3 → Ragnarok → Ended

---

## Setup

1. Drop the jar into your `plugins/` folder
2. Start the server — a default `config.yml` will generate
3. Run `/gvm start` to kick off the event

MythicMobs is optional. If it's not installed, Avatar Mode falls back to vanilla items.

---

## Commands

| Command | Who | What it does |
|---|---|---|
| `/gvm start\|stop` | Admin | Start or stop the event |
| `/vote <player>` | Anyone | Vote for someone to become a god |
| `/pray [message]` | Mortals | Pray to your god / claim daily login reward |
| `/bless <player> <message>` | Gods | Send a message to a follower |
| `/god bless <player> <power>` | Gods | Apply a blessing to a follower |
| `/god curse <shrine_id> <type>` | Gods | Curse a shrine |
| `/god rival\|unrival` | Gods | Declare or drop a rivalry (+20% damage) |
| `/god truce\|truce accept` | Gods | Propose or accept a truce |
| `/god avatar` | Gods | Activate Avatar Mode (costs 500 faith) |
| `/betray` | Mortals | Start a betrayal ritual (Day 2+, nighttime only) |
| `/sacrifice <god>` | Mortals | Sacrifice 5 HP to build toward banishing a god |
| `/fallen steal` | Fallen God | Steal a god's unique power |

---

## Shrines

Build a 3×3 stone brick platform with a gold block in the center. Once complete, you'll be prompted to dedicate it to a god. One shrine per mortal.

Upgrade your shrine by surrounding it with iron, gold, or diamond blocks:
- Iron → 1.5× faith
- Gold → 2× faith
- Diamond → 3× faith

---

## Key mechanics

**Faith** — generated passively by shrines every minute. Gods spend it on powers.

**Betrayal** — mortals can switch gods on Day 2+. Stand near your shrine at night and run `/betray`. Don't move more than 5 blocks or it cancels. You take 50% extra damage during the ritual.

**Ragnarok** — activates in the final 12 hours of Day 3. Gods lose damage immunity. Rebellion banners (earned through betrayal) can be placed to mark territory.

**Avatar Mode** — costs 500 faith, gives the god 100 HP, flight, and a powerful weapon for 10 minutes. One use per event. If the god dies during Avatar Mode, their faction is eliminated.

**Great Betrayal** — when Ragnarok starts, the mortal who was betrayed the most becomes the Fallen God and gets `/fallen steal`.

**Mass Sacrifice** — mortals can chip away at a god's standing by sacrificing HP. At 50 sacrifice points, the god gets banished for 1 hour.

**Daily quests** (mortals):
- Pray at 3 distinct shrines
- Sacrifice a diamond at your shrine
- Convert a non-believer

**Titles** earned at end of event: The Loyal, The Turncloak, The Merciful, The Wrathful. Kill a god during Ragnarok and you get Godslayer.

---

## Config

```yaml
event:
  day-duration-hours: 24       # length of each day
  voting-duration-minutes: 10  # how long voting lasts
  faith-cap: 10000             # max faith a god can hold

sacrifice:
  banish-threshold: 50         # sacrifice points to banish a god
  banish-duration-hours: 1
  hp-cost: 5                   # HP lost per sacrifice
  min-hp-to-sacrifice: 6       # can't sacrifice if HP is this low or below
```

Full config is in `plugins/GodsVsMortals/config.yml` after first run.

---

## Permissions

| Permission | Default | Description |
|---|---|---|
| `godsvsmortals.admin` | OP | Start/stop the event |
| `godsvsmortals.play` | Everyone | Participate as a mortal |
| `godsvsmortals.god` | false | Use god commands (auto-assigned on election) |
| `godsvsmortals.fallen` | false | Use fallen god commands (auto-assigned) |
