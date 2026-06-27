# HardcoreReset

HardcoreReset is a server-side Fabric mod for Minecraft that tracks player deaths and resets the entire world when a death threshold is reached. It utilizes a safe "limbo" lobby dimension, allowing the root overworld to act as a disposable and resettable environment without kicking players from the server.

## Features

- **Death Tracking:** Tracks the number of lives each player has remaining in the current cycle.
- **World Reset:** Automatically initiates a world reset sequence when a player depletes their lives.
- **Limbo Lobby:** Moves players to a safe lobby dimension during the reset process.
- **Configurable Rules:** Extensive configuration for death limits, PvP death counting, statistic wiping, seed changing, and temp-bans for the instigator.

## Commands

All commands require OP permissions (level 2+).

### Configuration Commands

These commands allow you to modify the server's reset behavior and rules on the fly:

- `/hardcorereset config set total_world_resets <value>` - Sets the total number of times the world has been reset.
- `/hardcorereset config set default_death_limit <value>` - Sets the default amount of lives each player gets per world cycle.
- `/hardcorereset config set reset_countdown_seconds <value>` - Sets the timer duration before the reset actually triggers after a final death.
- `/hardcorereset config set count_pvp_deaths <true|false>` - Toggles whether PvP deaths count towards a player's limit.
- `/hardcorereset config set wipe_advancements <true|false>` - Toggles whether advancements are wiped upon reset.
- `/hardcorereset config set wipe_stats <true|false>` - Toggles whether player stats are wiped upon reset.
- `/hardcorereset config set change_seed_on_reset <true|false>` - Toggles whether the world seed changes after a reset.
- `/hardcorereset config set instigator_ban_type <type>` - Sets the ban type for the player who causes the reset (e.g., none, temp, perm).
- `/hardcorereset config set instigator_temp_ban_minutes <value>` - Sets the duration (in minutes) for the instigator's temporary ban if applicable.

### Dimension Commands

- `/hardcorereset dimension add <id>` - Adds a dimension to the exemption list (won't be reset).
- `/hardcorereset dimension remove <id>` - Removes a dimension from the exemption list.

### Player Management Commands

- `/hardcorereset player <player> set_limit <lives>` - Sets a custom life limit for a specific player and resets their current cycle deaths.
- `/hardcorereset player <player> clear_limit` - Clears any custom life limit set for a specific player, returning them to the default limit.
