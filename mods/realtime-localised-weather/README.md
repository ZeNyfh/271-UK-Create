# Realtime Localised Weather

Realtime Localised Weather is a NeoForge 1.21.1 mod for UKGeo worlds that replaces vanilla global overworld weather with server-authoritative, geographically localised, real-time weather tiles sourced from Open-Meteo.

This mod is required on both sides:

- dedicated servers
- all connecting clients
- both sides of a singleplayer integrated server

Clients that do not support the negotiated Realtime Localised Weather protocol are rejected. Clients also reject servers that do not provide the required protocol.

UKGeo is a required dependency. Serene Seasons is optional.

## What it does

- The server converts active UKGeo regions from Minecraft block coordinates to British National Grid and then to WGS84 latitude/longitude.
- The server batches active tile centres into asynchronous Open-Meteo requests.
- The server owns authoritative regional precipitation, thunder eligibility, gameplay severity, cache state, overrides, and synchronisation.
- Clients poll the server for the newest authoritative precipitation snapshots.
- The client uses vanilla rain and snow textures with local interpolation, density, fog, and weather sound mixing.
- Different clients may render different densities while still agreeing on gameplay weather.

## Weather authority modes

- `LIVE`: Open-Meteo drives regional weather and vanilla natural weather is suspended in UKGeo worlds.
- `MANUAL`: administrator overrides remain active until cleared or expired.
- `VANILLA`: regional weather is disabled and normal vanilla weather resumes.

Default mode for UKGeo worlds is `LIVE`.

## Serene Seasons behaviour

When Serene Seasons is installed, the default precipitation policy is `SERENE_SEASONS_WINTER_SNOW`.

That means:

- real rain in winter becomes Minecraft snow
- winter thunderstorms with liquid precipitation become thundersnow
- real clear weather remains clear
- explicit Open-Meteo snow remains snow, even outside winter
- tropical biomes are not forced to snow by default

Serene Seasons does not create precipitation on its own. If Open-Meteo reports no precipitation, winter still remains dry.

## Open-Meteo integration

- Requests are performed only by the logical server.
- Multiplayer clients do not contact Open-Meteo.
- Multiplayer clients poll their Minecraft server for the latest cached tile snapshots and receive server-authoritative precipitation data.
- Requests use Java 21 `HttpClient.sendAsync`.
- Cached snapshots remain usable during upstream outages.
- Expired or failed data are marked stale rather than crashing or freezing the server.

Open-Meteo variables used:

- `precipitation`
- `rain`
- `showers`
- `snowfall`
- `weather_code`
- `visibility`
- `temperature_2m`
- `relative_humidity_2m`
- `wind_speed_10m`
- `wind_direction_10m`
- `wind_gusts_10m`

Open-Meteo attribution: Weather data by [Open-Meteo](https://open-meteo.com/).

## Installation

### Server

1. Install NeoForge 21.1.230 for Minecraft 1.21.1.
2. Install Java 21.
3. Place both `ukgeo` and `realtime_localised_weather` in the server `mods/` directory.
4. Start the server once to generate config files.
5. Configure `config/realtime_localised_weather-server.toml` as needed.

### Client

1. Install NeoForge 21.1.230 for Minecraft 1.21.1.
2. Place both `ukgeo` and `realtime_localised_weather` in the client `mods/` directory.
3. Join a server running compatible versions of both mods.
4. Adjust `config/realtime_localised_weather-client.toml` to tune visuals only.

## Commands

The mod registers `/realtimeweather` commands for operators:

- `/realtimeweather status`
- `/realtimeweather mode <live|manual|vanilla>`
- `/realtimeweather override clear`
- `/realtimeweather override rain <severity> [duration]`
- `/realtimeweather override snow <severity> [duration]`
- `/realtimeweather override thunder <severity> [duration]`
- `/realtimeweather override region <zoneX> <zoneZ> <type> <severity> [duration]`
- `/realtimeweather clearoverride`
- `/realtimeweather refresh`
- `/realtimeweather sample <x> <z>`

## Runtime model

- The server does not send particles or client fog values.
- The client interpolates neighbouring tile snapshots and derives visual density locally.
- Server gameplay checks are position-aware and dry regions stay dry even if nearby tiles are wet.
- Regional snow accumulation and lightning are bounded and operate only in loaded chunks.

## Client visual settings

Client settings affect visuals only:

- precipitation density
- snow density
- fog
- sound
- wind slant
- splashes
- render distance

These options do not change gameplay severity, server rain checks, snow placement, or lightning authority.

## Documentation

- [Configuration](docs/CONFIGURATION.md)
- [Protocol](docs/PROTOCOL.md)

## Compatibility notes

- Vanilla weather is replaced only where the weather manager is active in the configured UKGeo overworld.
- Other dimensions keep vanilla weather behaviour.
- Serene Seasons foliage colours, crop progression, and season progression are not replaced.
- Local weather uses vanilla-style textured precipitation; production compatibility still depends on in-game validation with the target modpack.
