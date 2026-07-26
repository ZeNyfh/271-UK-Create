# Weather protocol

Realtime Localised Weather uses NeoForge custom payloads with an explicit protocol version.

## Compatibility

- The mod is required on both client and server.
- The server and client must both register the same protocol version.
- Incompatible clients are disconnected with a human-readable message.
- Clients also reject servers that do not provide the required weather protocol.

## Payloads

Configuration and bootstrap payloads:

- `WeatherProtocolPayload`
- `UkGeoReferencePayload`
- `WeatherAuthorityModePayload`
- `WeatherInitialGridPayload`

Client poll payloads:

- `WeatherPollRequestPayload`

Delta payloads:

- `WeatherTileUpdatePayload`
- `WeatherTileRemovePayload`
- `WeatherLightningPayload`

## Data ownership

The server sends:

- authoritative tile keys
- canonical weather snapshot values
- resolved precipitation type
- gameplay severity
- stale state
- authority mode

The server does not send:

- individual particles
- fog colour decisions
- sound volume curves
- client-only render density

The client derives interpolation, precipitation density, fog, and sound locally from the synchronized snapshot data.

## Sync model

1. Client and server negotiate protocol compatibility.
2. The server sends georeference metadata for the current UKGeo world.
3. The server sends the relevant initial tile grid for the player.
4. The client periodically sends `WeatherPollRequestPayload` for its current dimension.
5. The server answers poll requests with the latest relevant initial grid or tile deltas/removals from its authoritative cache.
6. The server may still send authority mode changes and lightning notifications immediately.

## Threading

- Network payload handling copies data into immutable client weather state.
- Open-Meteo fetches are asynchronous and are not run on tick or render threads.
- The client render path reads already-synchronized state only.
