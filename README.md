# VoidOptimize

VoidOptimize is a conservative Paper 1.21.11 performance plugin focused on reducing plugin overhead, avoiding unnecessary entity scans, smoothing chunk requests, and backing off automatically during lag. It is designed for small-memory servers where every main-thread millisecond matters.

## VoidionMC panel

Use `/voidoptimize panel`. The GUI is locked to `VoidionMC`. It uses a 54-slot paged layout with grouped controls, live MSPT/player/chunk/entity status, navigation, safety indicators, and 100+ optimization controls/catalog entries.

The panel does not pretend that a Bukkit plugin can replace Paper's internal engine. Controls that require Paper configuration or NMS are clearly treated as advanced/catalog controls. The plugin's real runtime controls are adaptive scheduling, low-overhead player sampling, guarded async chunk prefetch, directional movement prediction, duplicate-request protection, overload backoff, profiling, metrics, and protected projectile handling.

## Performance model

The old implementation performed nearby-entity scans without changing server state. That could cost CPU while providing no actual optimization. The current implementation removes that scan and uses an O(players) lightweight sampler instead.

Chunk prefetch is deliberately conservative. It only runs when MSPT is healthy, limits concurrent requests, deduplicates requests, predicts player movement, and backs off under load. New chunk generation is disabled by default because forcing generation ahead of players can make a 4 GB server less stable rather than faster. Paper's chunk worker/load/generation limits should be tuned for the actual CPU and storage.

## 100+ players / 4 GB RAM

This plugin is designed to keep its own overhead small, but no plugin can guarantee 100+ players at 20 TPS on arbitrary hardware with 4 GB RAM. CPU single-thread performance, SSD/NVMe speed, world size, simulation distance, entity density, redstone, farms, other plugins, and player movement all matter. Paper itself recommends profiling with spark instead of guessing at the bottleneck.

## Ping and FPS

A server plugin cannot directly lower a player's physical network latency or guarantee client FPS. It can reduce server-side stalls that feel like lag and avoid unnecessary chunk/plugin work. Client FPS remains dependent on the player's hardware and client settings.

## Gameplay safety

No entity deletion, forced garbage collection, watchdog disabling, blind chunk unloading, random-tick rewriting, or default mob-AI throttling is used. These techniques can change gameplay or make a lag spike worse. Paper does expose optimizations such as explosion caching and pathfinding-update controls, but some change edge-case behavior; those should be applied deliberately in Paper configuration rather than silently from this plugin.

## Commands

`/voidoptimize panel`
`/voidoptimize status`
`/voidoptimize profile`
`/voidoptimize reload`
