# Vehicle — drivable Blockbench vehicles for Paper 26.x

Create vehicle types in game, give them a `.bbmodel` body, spawn them, and drive with WASD.
Real hitbox, crash and ram physics, multiple seats with adjustable positions, a generated
resource pack you host yourself.

## Install

1. Drop `Vehicle-1.0.0.jar` into `plugins/` (Paper 26.2, Java 25+).
2. Start the server once: it creates `plugins/Vehicle/models/` and `plugins/Vehicle/generated/`.
3. Put your Blockbench files into `plugins/Vehicle/models/` — `jeep.bbmodel` becomes the model `jeep`.
   Generic, Java block/item, Bedrock and GeckoLib formats all work (animations are ignored,
   meshes are skipped — cubes only). Textures must be embedded in the file (Blockbench's default)
   or placed next to it with the same name.
4. Restart (or `/vehicle pack`). The pack is written to
   `plugins/Vehicle/generated/Vehicle-pack.zip`, its sha1 to `Vehicle-pack.sha1`, and a
   per-model report to `pack-report.txt`.
5. Host the zip and set `resource-pack=<url>` and `resource-pack-sha1=<sha1>` in
   `server.properties` (update the sha1 every time you regenerate the pack).

## Commands (`vehicle.admin`, default op)

| Command | What it does |
|---|---|
| `/vehicle create <id> [model]` | new vehicle type |
| `/vehicle edit <id> <property> <value…>` | change a type; applies live to spawned vehicles |
| `/vehicle spawn <id>` | spawn one where you stand (console: `spawn <id> <x> <y> <z> [world]`) |
| `/vehicle remove [radius\|all]` | remove the vehicle you look at, or all within a radius, or all |
| `/vehicle delete <id>` | delete a type and every spawned vehicle of it |
| `/vehicle list`, `/vehicle info <id>` | overview / full settings |
| `/vehicle models` | models found in `models/` and whether they converted |
| `/vehicle give <player> <id> [amount]` | a placeable vehicle item (right-click a block) |
| `/vehicle pack` | regenerate the resource pack |
| `/vehicle reload` | reload config + types, regenerate the pack |

### Edit properties

| Property | Meaning | Default |
|---|---|---|
| `name <text>` | display name | id |
| `model <name>` | `.bbmodel` file name (tab-completes) | none |
| `speed <blocks/s>` | top speed | 10 |
| `acceleration <b/s²>` | throttle | 6 |
| `brake <b/s²>` | braking | 14 |
| `reverse <0-1>` | reverse top speed as a fraction of `speed` | 0.4 |
| `friction <0-1>` | per-tick coast factor (lower = stops sooner) | 0.985 |
| `turn <deg/tick>` | steering rate at speed | 3.5 |
| `grip <0-1>` | 1 = follows the nose instantly, lower = slides | 0.6 |
| `scale <n>` | model scale | 1 |
| `modelyaw <deg>` | rotate the model on the vehicle; **if it drives backwards, set 0 or 180** | 180 |
| `offset <x> <y> <z>` | nudge the model (blocks) | 0 0 0 |
| `hitbox <blocks>` | collision box size (blocks you bump into / get pushed by) | 1.8 |
| `stepheight <blocks>` | what it climbs without jumping | 1 |
| `health <hp>` | hit points; crashes, falls and attacks lower it, 0 = boom | 100 |
| `crash <true/false>` | crash / ram damage on or off | true |
| `crashdamage <mult>` / `ramdamage <mult>` | damage multipliers | 1 / 1 |
| `sound <key>` / `volume <n>` / `horn <key>` | engine loop and horn (F while seated), `none` to mute | minecart / 0.6 / goat horn |
| `seats <count>` | number of seats (seat 1 drives) | 2 |
| `seat <n> <x> <y> <z>` | seat position (see below) | |
| `seat <n> x\|y\|z <value>` | change one axis | |
| `seat <n> size <w> <h>` / `seatsize <w> <h>` | the invisible click box around a seat (bigger = enter from further away) | 1.6 × 1.2 |

Seat coordinates are **model space in blocks = Blockbench pixels ÷ 16**, measured from the
model's origin, before `scale`: `x` sideways, `y` up (roughly where the rider's feet go),
`z` along the length. Read the position of the seat in Blockbench, divide by 16, done.
If every rider sits too high or low, change `seat-rider-offset` in `config.yml`.

## Driving

Right-click anywhere on the vehicle (or a seat's click box) → nearest free seat; the first seat
drives. **W/S** throttle and brake/reverse, **A/D** steer, **space** handbrake, **sneak** to get
out (you step out beside the vehicle), **F** horn. The driver sees speed and vehicle health on
the action bar. Sneak + right-click an empty vehicle you placed to pick it up as an item.

## Physics

The vehicle is an invisible mob with a scaled hitbox (`hitbox`): it has gravity, climbs steps,
collides with blocks and pushes entities. Driving into a wall at speed is a crash: the vehicle
and its riders take damage, scaled by speed. Driving into mobs/players rams them (damage +
knockback). Vehicles bumping into each other hurt both. Hard landings cost health. At 0 health
the vehicle explodes (effects only) and disappears. All numbers are in `config.yml` under `crash`.

## Models: what the converter does

Java item models can only hold geometry in a 48-block-pixel cube, so the whole model is
centred, scaled into that range and the display entity scales it back — any size works, the
Blockbench origin stays at the vehicle's position and 16 px = 1 block. Rotations that are
multiples of 90° are exact; others are rounded to 22.5° steps on one axis (Java model limit).
Problems are listed in `generated/pack-report.txt` and on `/vehicle models`.
