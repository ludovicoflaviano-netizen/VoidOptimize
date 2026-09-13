# VoidOptimize

VoidOptimize is a conservative Paper 1.21.11 server optimization plugin focused on reducing CPU work and unnecessary allocations without breaking normal gameplay.

## What it does

- Uses bounded, player-aware workload culling instead of world-wide entity scans.
- Inspects only nearby entities around online players and stops at a configurable per-pass budget.
- Adaptively backs off its own work when the server is under heavier tick pressure.
- Keeps Bukkit/Paper world-state work synchronous and lightweight.
- Avoids large long-lived caches and background worker threads.
- Reports JVM memory without forcing garbage collection.
- Protects ENDER_PEARL, WIND_CHARGE, and BREEZE_WIND_CHARGE by default.
- Does not delete dropped items, mobs, projectiles, villagers, redstone components, or other gameplay entities.
- Does not disable mob AI, unload chunks, change simulation distance, or alter combat.
- Provides `/voidoptimize status`, `/voidoptimize profile`, and `/voidoptimize reload`.

## Culling model

The culling in this plugin is **server-side workload culling**. A normal Paper plugin cannot honestly provide client rendering culling. Blindly freezing distant entities can also break farms, redstone, villagers, combat, and projectiles. VoidOptimize therefore limits the work performed by the optimizer itself rather than deleting or freezing gameplay entities.

## Memory and CPU

A plugin cannot guarantee a fixed JVM heap reduction. Java controls heap sizing and garbage collection. The practical goals are lower allocation rate, fewer repeated scans, bounded plugin state, and lower plugin CPU time. Overall server performance also depends heavily on Paper configuration and JVM settings.

VoidOptimize intentionally never calls `System.gc()`.

## Configuration

Safe defaults are provided in `config.yml` for the culling interval, entity budget, player radius, adaptive backoff threshold, and protected entity types. Automatic cleanup is disabled by design.

Use `/voidoptimize reload` after changing the configuration. A full restart is preferred for production changes.

## Commands

```text
/voidoptimize status
/voidoptimize profile
/voidoptimize reload
```

Permission: `voidoptimize.admin`

## Build

Requires Java 21 and Maven.

```bash
mvn -U -B clean package
```

The plugin JAR is generated in `target/`.
