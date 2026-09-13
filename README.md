# VoidOptimize

VoidOptimize is a conservative Paper 1.21.11 performance plugin. It reduces its own overhead, adapts work to current MSPT, and smooths chunk requests without destructive gameplay changes.

## VoidionMC panel

Use `/voidoptimize panel`. The GUI is locked to the exact username `VoidionMC`. It contains 120+ optimization controls/catalog entries across AI, pathfinding, entities, chunks, memory, scheduling, diagnostics, and safety.

Controls that require changes inside Paper's server engine are intentionally shown as catalog entries rather than pretending a Bukkit plugin can safely hook them. Real controls include adaptive budgets, emergency backoff, chunk prefetch, directional prefetch, metrics, profiling, projectile protection, and safe task lifecycle.

## Important limitations

A server plugin cannot guarantee 300+ client FPS. Client FPS depends on the player's GPU/CPU, resolution, shaders, resource packs, entity rendering, and client settings. This plugin targets server TPS/MSPT and chunk delivery.

Mob-AI simplification, random-tick changes, redstone throttling, blind entity deletion, forced GC, watchdog disabling, and similar techniques are deliberately not enabled because they change gameplay or can make failures worse.

Paper itself exposes server-side chunk concurrency and rate controls, so those should be tuned for the actual CPU, storage, view distance, and player count rather than blindly maximized.

## Commands

`/voidoptimize panel`
`/voidoptimize status`
`/voidoptimize profile`
`/voidoptimize reload`
