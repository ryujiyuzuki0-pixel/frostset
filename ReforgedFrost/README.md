# ReforgedFrost

Paper plugin (1.21.4+, Java 21) that adds the **Reforged Frost** armor as a real, animated 4-piece set.
Players need Minecraft **1.21.11+** (the model uses free-angle cube rotation) and server resource packs enabled.
No MythicArmors needed.

## How the set is rendered
Vanilla can only draw a 3D model on the head slot, so the plugin draws the armor itself:
- `tools/build_pack.py` splits the `.bbmodel` per body part (head, torso, dust, arms, hips, legs, boots) into item models,
  plus an inventory icon per piece. The vanilla armor layers are blank so nothing is drawn twice.
- `ArmorRig` shows one display entity per worn part and moves them every tick. Wear only boots and you only see boots.
- Animation: arms and legs swing while moving, the upper body leans while sneaking, the floating dust around the chest
  orbits and bobs. Tune or turn off in `config.yml` under `animation`.
- The wearer does not see their own helmet in first person (it would sit in your face). Others see it.
- Not animated: swimming, gliding and sleeping hide the parts instead of showing them detached.

## Build (GitHub Actions)
Push to a repo. Every push regenerates the pack, builds the jar and publishes `ReforgedFrost.jar` + `pack.zip`
to the **Releases** page as `latest`. Local: `python3 tools/build_pack.py` (needs Pillow), then `gradle build` (Java 21).

## Install
1. Put `ReforgedFrost.jar` in `plugins/` and start the server.
2. The pack is sent on join. By default players download it from the GitHub release (`pack.external-url`, repo must be
   public, no port needed). To self-host, blank `external-url` and open `pack.port` (8123).
3. `/frost give <player>` and wear the pieces.

## Commands (perm `reforgedfrost.admin`, default op)
- `/frost give <player> [leather|netherite] [set|helmet|chestplate|leggings|boots]`
- `/frost pack [player]` resend the pack
- `/frost helmet` show or hide your own helmet for yourself (hidden by default, it blocks the first-person camera)
- `/frost status` shows what is wrong if the texture is missing
- `/frost reload`

## Set bonus
- Any piece worn: Speed I
- All 4 pieces worn: Speed II and **+5% max health** (21 HP instead of 20)
- Tune in `config.yml` under `set-bonus` (`full-health-bonus`, 0 = off).

## Other resource packs
The pack is sent under its own fixed id and never clears other packs, so it coexists with Nexo/Oraxen/ItemsAdder.
The pack contains no shaders.

## Legacy MythicArmors mode
Off by default. `install-model: true` copies the model into MythicArmors and `pack.use-mythicarmor: true` serves its pack.
Do not combine it with the built-in renderer, the armor would be drawn twice.
