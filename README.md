# VoidOptimize

VoidOptimize is a conservative Paper 1.21.11 performance plugin designed to reduce its own overhead, smooth chunk requests, and back off automatically when the server is under load.

## Optimization layers

- Adaptive workload budgeting: the plugin changes its own scan budget from measured MSPT instead of running a fixed workload at all times.
- Emergency backoff: when the measured MSPT is unhealthy, plugin work pauses until the server recovers.
- Direction-aware chunk prefetch: when players move into a new chunk, nearby chunks are requested first so exploration can feel smoother.
- Strict chunk request limits: both per-pass and in-flight limits prevent the plugin from creating a chunk-loading storm.
- No new-chunk generation by default: generation can be enabled manually, but it is CPU/storage intensive.
- Low-overhead metrics: memory, heap, MSPT, queue depth, and optimizer timings are available without forced garbage collection.
- Projectile protection: ender pearls, wind charges, and breeze wind charges remain protected.

## What it does not do

This plugin does not delete items or mobs, disable mob AI, unload chunks blindly, alter redstone, change random tick speed, force GC, or disable the Paper watchdog. Those approaches can break farms/gameplay or make failure modes worse.

The plugin cannot make chunk generation infinitely faster. Paper controls the actual asynchronous chunk-loading pipeline; the plugin improves perceived exploration by requesting safe nearby chunks ahead of players. Paper's API explicitly provides asynchronous chunk loading for work that does not need an immediate chunk and lets the server control the load speed.

For server-wide optimization, also tune Paper's own configuration. For example, Paper exposes chunk-system IO/worker thread controls and server-side view distance/entity broadcast settings. These should be tuned for the actual CPU, storage, player count, and world workload rather than blindly maximized.

## Commands

- `/voidoptimize status`
- `/voidoptimize profile`
- `/voidoptimize reload`

Permission: `voidoptimize.admin`

## Important

There is no honest way to guarantee "100% optimization" or zero freezes/crashes. Performance depends on hardware, JVM memory, Paper configuration, plugins, datapacks, world generation, storage latency, and player behavior. This project aims for maximum safe optimization without changing core gameplay.
