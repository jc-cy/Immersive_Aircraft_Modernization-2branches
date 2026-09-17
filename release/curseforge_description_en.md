# Immersive_Aircraft_Modernization

Immersive_Aircraft_Modernization adds the Overclocked Cruise Guidance Module to Immersive Aircraft, providing automatic route navigation for aircraft and hovering vehicles.

## Core Features

- Install the module in an Immersive Aircraft upgrade slot, or edit route data while holding it.
- Create and name multiple routes with default altitude, per-waypoint altitude, and named waypoints.
- Choose Super Acceleration, Acceleration, Normal, or Eco cruise mode for each route.
- Choose a landing strategy for each route: holding pattern, normal landing, or vertical landing where supported.

## Routes & Preloading

- Route preloading offers three modes: None, Three-wide flight, and L-shaped flight.
- Three-wide flight keeps a three-chunk-wide corridor; L-shaped flight uses a single-chunk path for long diagonal legs.
- Preloading reduces terrain-loading pressure during high-speed navigation.

## HUD Information

- Displays speed, fuel amount, fuel time remaining, and estimated flight time remaining.
- Flight time is estimated from horizontal route distance and current speed; L-shaped legs use their actual two-axis path estimate.
- Supports item fuel and liquid fuel containers.
- Includes route name, start point, previous stop, next stop, and final destination.

## Configuration

- Customize power bonuses and fuel-consumption bonuses for all four cruise modes.
- Configure the horizontal cruise speed limit for airships, airboats, and other hovering vehicles; set it to `0` to disable the limit.

## Controls & Compatibility

- `,` opens route settings.
- `.` starts, pauses, resumes, or exits automatic navigation.
- Includes pilot-only editing and navigation control, passenger read-only information, collision protection, precise landing control, and advancements.

Required dependency: Immersive Aircraft.
Built for Minecraft 1.20.1 Forge.
License: All Rights Reserved (ARR). See the bundled `LICENSE` and `DISCLAIMER.md`.
