# HuntingUtilitiesBETA

**Meteor Client addon** providing utilities focused on **stash hunting**, **base hunting**, and general survival/QoL on the 2b2t anarchy server (and similar environments).

Currently in **BETA** — expect bugs, incomplete features, and frequent updates.

Current modules;

- Dungeon Assistant
- Elytra Assistant
- Graveyard
- Grid Lock
- Loot Lens
- Portal Maker
- Portal Tracker
- Rocket Pilot
- Server Healthcare System

More modules coming soon — 

## Requirements

- Minecraft **1.21.x** (or whichever version your addon targets — check `gradle/libs.versions.toml`)
- [Fabric Loader](https://fabricmc.net/use/installer/)
- [Meteor Client](https://meteorclient.com/) (latest snapshot recommended for 2b2t compatibility)
- This addon JAR

## Installation

1. Download the latest release JAR from [Releases](https://github.com/rithsgit/HuntingUtilitiesBETA/releases) (or build from source — see below).
2. Place the JAR in your Minecraft `mods` folder **alongside** Meteor Client.
3. Launch Minecraft using the Fabric profile.
4. In-game: Open Meteor GUI (`Right Shift` by default) → your HuntingUtilities modules should appear in the module list.

## Building from Source

This project uses the Meteor Addon Template structure.

### Setup

- Clone the repo:  
  ```bash
  git clone https://github.com/rithsgit/HuntingUtilitiesBETA.git
  cd HuntingUtilitiesBETA
