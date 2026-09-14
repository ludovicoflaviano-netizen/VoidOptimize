# VoidOptimize 2.0

VoidOptimize is a lightweight Paper 1.21.11 optimization layer. It is intentionally adaptive: when the server is healthy it performs safe background work, and when MSPT rises it backs off before the optimizer becomes part of the problem.

## Design
- Low-allocation adaptive scheduling.
- No world-wide entity scans.
- No entity deletion.
- No forced garbage collection.
- No watchdog manipulation.
- No mob-AI shutdown.
- No random-tick, redstone, physics, drop, or farm changes.
- Bounded asynchronous chunk prefetch using Paper's async chunk API.
- Duplicate chunk-request protection and in-flight limits.
- New chunk generation ahead of players is disabled by default.
- Ender pearls, wind charges, and breeze wind charges are always protected.
- VoidionMC-only performance dashboard.

## Adaptive profiles
`LOW_RAM`, `BALANCED`, `HIGH_PLAYER_COUNT`, and `EMERGENCY` are selected from current memory pressure, player count, and measured tick delay. Emergency mode stops optional prefetch work rather than trying to fight the main thread.

## Commands
- `/voidoptimize panel` - VoidionMC dashboard.
- `/voidoptimize status` - current performance state.
- `/voidoptimize profile` - performance snapshot.
- `/voidoptimize reload` - reload safe plugin settings.

## Important
A plugin cannot guarantee a fixed FPS, physical ping, 100+ players on every 4 GB machine, or zero crashes. It can reduce its own CPU and allocation overhead and avoid adding work during an overloaded tick. Paper's own chunk and world settings should be tuned for the actual CPU, storage, view distance, entity density, and player workload.
