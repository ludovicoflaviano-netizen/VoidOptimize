# VoidOptimize

VoidOptimize is a conservative Paper 1.21.11 optimizer. It focuses on reducing its own CPU cost, smoothing bursty chunk requests, bounding work, and backing off during overload without deleting gameplay entities or changing core Minecraft mechanics.

## Optimization layers

- Player-local workload culling with a hard per-pass entity budget.
- Adaptive emergency mode. When measured MSPT becomes unhealthy, non-essential optimizer work stops until the server recovers.
- Bounded asynchronous chunk prefetch around active players using Paper's async chunk API.
- Small in-flight and per-pass chunk limits prevent a chunk-load storm.
- Optional new-chunk generation prefetch is disabled by default because generation itself is expensive.
- Low-overhead memory and heap metrics.
- Ender pearls, wind charges, and breeze wind charges are protected.
- No forced GC, item deletion, mob deletion, chunk unloading, AI disabling, redstone changes, combat changes, or farm-breaking cleanup.

## Faster chunk loading

The plugin cannot make chunk generation itself infinitely faster. It can reduce the visible cost of exploration by requesting nearby chunks ahead of the player while the server is healthy. Paper controls the actual asynchronous loading speed. New-chunk generation is therefore disabled by default; enable it only after testing the hardware and world workload.

## Freeze and crash protection

The optimizer protects the server from additional optimizer-induced overload by using strict budgets and emergency backoff. It does not disable or bypass the Paper watchdog, and it cannot guarantee that a server will never freeze or crash. World corruption, JVM memory exhaustion, broken plugins, operating-system limits, and extreme chunk generation can still cause failures.

## Commands

- `/voidoptimize status`
- `/voidoptimize profile`
- `/voidoptimize reload`

Permission: `voidoptimize.admin`

## Configuration

The safe defaults are designed for normal survival/SMP gameplay. Increase chunk prefetch only when profiling shows that the server has spare CPU capacity. Do not use aggressive chunk generation on low-end hardware.

For the best overall result, also tune Paper's own view distance, simulation distance, chunk system, entity activation, JVM heap, and storage. A plugin cannot safely replace all server-level and JVM optimizations with one universal setting.
