# Configuration

Realtime Localised Weather creates separate server and client configuration files.

## Server: `config/realtime_localised_weather-server.toml`

Primary controls:

- `enabled`: master switch for the regional system
- `authority_mode`: `LIVE`, `MANUAL`, or `VANILLA`
- `api_base_url`: Open-Meteo base URL
- `weather_model`: optional Open-Meteo model hint
- `refresh_interval_minutes`
- `minimum_forced_refresh_minutes`
- `stale_cache_hours`
- `hard_cache_expiry_hours`
- `maximum_concurrent_requests`
- `zone_size_blocks`
- `active_zone_radius`
- `prefetch_zone_radius`
- `inactive_tile_retention_minutes`

Seasonal precipitation controls:

- `seasonal_precipitation_policy`
- `early_winter_liquid_result`
- `mid_winter_liquid_result`
- `late_winter_liquid_result`
- `winter_thunder_result`
- `winter_freezing_rain_result`
- `tropical_winter_conversion`

Gameplay controls:

- `enable_gameplay_rain`
- `enable_snow_accumulation`
- `enable_ice_formation`
- `enable_cauldron_filling`
- `enable_fire_extinguishing`
- `enable_authoritative_lightning`
- `gameplay_severity_thresholds`
- `weather_command_behaviour`
- `request_budget_safeguards`

Operational guidance:

- changing `zone_size_blocks` should be treated as a restart or world-reload setting
- increasing `active_zone_radius` or `prefetch_zone_radius` raises request and cache pressure
- `hard_cache_expiry_hours` is the limit after which old data should no longer be trusted

## Client: `config/realtime_localised_weather-client.toml`

Visual controls only:

- `enabled`
- `precipitation_renderer`
- `cloud_renderer`
- `transition_seconds`
- `precipitation_render_distance_blocks`
- `cloud_render_distance_blocks`
- `precipitation_density_multiplier`
- `snow_density_multiplier`
- `cloud_density_multiplier`
- `enable_weather_fog`
- `enable_weather_sounds`
- `enable_wind_slant`
- `enable_splashes`
- `enable_cosmetic_distant_lightning`
- `enable_debug_overlay`
- `graphics_fallback_mode`

These values do not alter server gameplay weather.

## Defaults

The implementation ships with conservative defaults aimed at:

- 256 block weather tiles
- a 5×5 active grid around a lone player
- asynchronous refreshes at roughly 15 minute cadence
- stale-cache survival during temporary network failures
- custom precipitation and cloud rendering enabled on clients
