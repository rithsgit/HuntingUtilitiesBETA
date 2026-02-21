# Dungeon Tools / Quality-of-Life Mod Collection

A set of lightweight, focused Minecraft utility modules designed for efficient looting, scanning, automation, and travel — especially useful in survival, anarchy, or hardcore environments.

## Modules

### 1. Dungeon Assistant
Helps you quickly find and process valuable containers and structures in dungeons, mineshafts, strongholds, ancient cities, and hidden player bases.

**Main Features**
- Automatically opens nearby chests, trapped chests, and chest minecarts
- Checks for high-value items (default: enchanted golden apples, ender chests, shulkers of all colors)
- Plays alert sound when rare items are found
- Auto-closes and (optionally) auto-breaks empty containers after a short delay
- Can also auto-break spawners (with configurable range & delay)
- Prioritizes spawners over chests if both are nearby (toggleable)
- Tracks recently broken spawners and shows a temporary tall beam at the location
- Adds **Steal** and **Dump** buttons to container screens for faster manual looting

**Safety Options**
- Disconnect on nearby player
- Disconnect / auto-disable on totem pop
- Auto-eat golden apples when low on health
- Auto-disable on critically low health while holding totem

### 2. Graveyard
Automatically detects and alerts you to high-value items dropped as entities (e.g. after PvP deaths, raids, or from dead bodies) within a configurable range.

**Main Features**
- Scans for dropped items matching your whitelist (default: elytra, totems, enchanted golden apples, netherite/diamond tools & armor, shulker boxes, fireworks, bows, flint & steel, etc.)
- Plays a sound and/or sends a chat message when a new matching item is detected
- Draws a tall beam from each found item up to build height (white by default, thickness and color adjustable)

**Optional Settings**
- Show beams only for the single nearest item
- Sort / prioritize items by distance to you
- Fade beam opacity based on distance (start & end fade distances)
- Beam stops rendering at player eye height (adjustable)

### 3. Gridlock
Automatically flies grid, spiral or lawnmower patterns while keeping safe height above ground and below ceilings — perfect for large-area scanning without constant manual control.

**Flight Patterns**
- Square Spiral
- Archimedean Spiral
- Diagonal Grid
- Lawnmower (parallel strips with return legs)

**Key Features**
- Configurable spacing (in chunks) between lines/loops
- Smart altitude control: maintains floor clearance + avoids ceilings (especially useful in Nether roof)
- Looks ahead to anticipate terrain changes (hills, cliffs, bedrock)
- Smooth turning toward next waypoint
- Pause/resume keybind
- Safety: auto-pause on damage or low health
- Optional auto-disable after max distance from start
- Renders green line to next target (toggleable)

### 4. Loot Lens
Helps with stash moving, base raiding, and container auditing.

**Supported Containers** (toggleable + custom colors per type)
- Chests / trapped chests
- Barrels
- Chest minecarts (including stacked)
- Furnaces / blast furnaces / smokers
- Hoppers
- Dispensers / droppers
- Brewing stands
- Crafters
- Decorated pots

**Features**
- Manual inventory check: Open highlighted container → auto-scans for shulkers / custom high-value items (e-gaps, elytra) → green glow + sound/chat alert (handles double chests)
- Auto item frames: Detects shulkers in normal/glow frames → pink/cyan box + beam + instant alert (no opening needed)
- **Steal** / **Dump** buttons on container GUIs (optional hotbar dump)
- Configurable range (up to 512 blocks), beams (width adjustable), shape mode, notifications
- Auto-cleanup of distant/removed containers, dimension reset

### 5. Portal Maker
Automatically builds a minimal Nether portal right in front of the player.

**Features**
- Places 10-block minimal frame (2×3 opening)
- Configurable place delay (1–12 ticks)
- Renders preview boxes for missing frame parts (toggleable, customizable colors/shape)
- Auto-lights with flint & steel (tries both bottom blocks)
- Optional auto-enter: walks/sprints/jumps into portal after lighting

### 6. Portal Tracker
Scans nearby chunks for Nether portals, End portals and End gateways → highlights them and tracks which ones you personally created.

**Highlights**
- Lit Nether portals (Overworld ↔ Nether)
- End portal frames (stronghold)
- End gateways (outer End islands)

**Features**
- Configurable scan range (16–64 chunks)
- Auto-marks nearby portals as “created by you” (adjustable radius)
- Counts & announces newly created portals in chat (with cooldown)
- Only-show-created mode available
- Render style: lines / fill / both + customizable colors per portal type
- Rainbow dynamic colors option (animated)
- Cleans up distant / unloaded portals automatically
- Handles dimension changes (clears data, restarts scan)
- Tracks session stats (total portals found + created by you)
- Reset counter button in settings
- Block-update aware (re-scans changed chunks)

### 7. Rocket Pilot
Fully automatic elytra + firework-rocket flight assistant.

**Altitude Control**
- Target Y level with tolerance
- Auto rocket firing when dropping too low

**Flight Modes**
- Classic smoothed pitch control
- Pitch40 mode — alternates steep climb (-40°) / descent (+40°) between two Y levels
- Oscillation mode — smooth sine-wave pitch ±40° for efficient long-distance speed (with optional peak rocket boosts)

**DrunkPilot Mode**
- Random chaotic yaw changes
- Optional quadrant bias → fly away from spawn / 0,0
- Adjustable intensity, frequency & smoothing

**Safety & Convenience**
- Auto-takeoff (jump + first rocket)
- Elytra durability monitor + warning + auto-swap to backup elytra
- Critical elytra → emergency flare landing + optional disconnect
- Low rockets → warning / auto-land
- Low health / totem pop → auto-disable or disconnect
- Collision avoidance (pull up + rocket when wall detected)
- Safe landing flare when near ground
- Limit rotation speed (anti-cheat friendly)
- Silent / no-swing rocket firing
- Auto-replenish rockets from inventory to hotbar

## General Notes
- Most modules are highly configurable (ranges, delays, colors, sounds, toggles, etc.)
- Safety features aim to reduce risk during automation or semi-afk usage
- Visual feedback (beams, lines, boxes, glows) uses modern rendering
- Designed to be modular — enable only what you need

Enjoy faster looting, safer travel, and less tedious grinding!
