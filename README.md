# VoidOptimize

VoidOptimize is a conservative Paper 1.21.11 optimization plugin. It is designed to reduce plugin-side overhead and avoid common "optimization" mistakes that cause gameplay problems.

## What it does

- Uses a very small, bounded tick-metrics window.
- Avoids long-lived object caches and background worker threads.
- Reports JVM memory without forcing garbage collection.
- Keeps optimization logic synchronous and lightweight.
- Explicitly protects `ENDER_PEARL`, `WIND_CHARGE`, and `BREEZE_WIND_CHARGE`.
- Does **not** clear dropped items, mobs, projectiles, or other entities by default.
- Does **not** call `System.gc()`.
- Has no automatic trash collector enabled.

## Important limitation

A Bukkit/Paper plugin cannot honestly guarantee "100%" more performance or arbitrarily reduce the JVM heap while keeping the exact same workload. Java memory is managed by the JVM, and the biggest gains usually come from Paper configuration, view/simulation distance, entity counts, plugins, chunk loading, and correct JVM flags.

VoidOptimize therefore uses safe changes rather than deleting gameplay entities or forcing garbage collection.

## Commands

`/voidoptimize` - show memory and lightweight performance status.

`/voidoptimize reload` - reload configuration.

Permission: `voidoptimize.admin`

## Build

Requires Java 21 and Maven.

```bash
mvn -U clean package
```

The plugin jar will be in `target/`.

## Installation

Copy the jar into `plugins/` and restart the Paper server. Do not use `/reload` on a production server.
