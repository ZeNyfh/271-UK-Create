package com.ukgeo.realtimelocalisedweather.network;

import com.ukgeo.realtimelocalisedweather.weather.GameplaySeverity;
import com.ukgeo.realtimelocalisedweather.weather.MeteorologicalPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ResolvedPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.ServerWeatherSnapshot;
import com.ukgeo.realtimelocalisedweather.weather.WeatherAuthorityMode;
import java.time.Instant;
import net.minecraft.network.RegistryFriendlyByteBuf;

final class PayloadUtils {
    private PayloadUtils() {
    }

    static void writeEnum(RegistryFriendlyByteBuf buffer, Enum<?> value) {
        buffer.writeVarInt(value.ordinal());
    }

    static <T extends Enum<T>> T readEnum(RegistryFriendlyByteBuf buffer, T[] values) {
        int ordinal = buffer.readVarInt();
        return values[Math.max(0, Math.min(values.length - 1, ordinal))];
    }

    static void writeSnapshot(RegistryFriendlyByteBuf buffer, ServerWeatherSnapshot snapshot) {
        buffer.writeLong(snapshot.observedAt().toEpochMilli());
        buffer.writeDouble(snapshot.latitude());
        buffer.writeDouble(snapshot.longitude());
        buffer.writeVarInt(snapshot.weatherCode());
        writeEnum(buffer, snapshot.precipitation());
        buffer.writeFloat(snapshot.precipitationRateMmPerHour());
        buffer.writeFloat(snapshot.rainRateMmPerHour());
        buffer.writeFloat(snapshot.snowfallRateCmPerHour());
        buffer.writeFloat(snapshot.temperatureCelsius());
        buffer.writeFloat(snapshot.relativeHumidity());
        buffer.writeFloat(snapshot.totalCloudCover());
        buffer.writeFloat(snapshot.lowCloudCover());
        buffer.writeFloat(snapshot.midCloudCover());
        buffer.writeFloat(snapshot.highCloudCover());
        buffer.writeFloat(snapshot.visibilityMetres());
        buffer.writeFloat(snapshot.windSpeedKmh());
        buffer.writeFloat(snapshot.windDirectionDegrees());
        buffer.writeFloat(snapshot.windGustKmh());
        writeEnum(buffer, snapshot.resolvedPrecipitation());
        writeEnum(buffer, snapshot.gameplaySeverity());
        buffer.writeFloat(snapshot.thunderPotential());
        buffer.writeBoolean(snapshot.stale());
        buffer.writeLong(snapshot.revision());
    }

    static ServerWeatherSnapshot readSnapshot(RegistryFriendlyByteBuf buffer) {
        return new ServerWeatherSnapshot(
            Instant.ofEpochMilli(buffer.readLong()),
            buffer.readDouble(),
            buffer.readDouble(),
            buffer.readVarInt(),
            readEnum(buffer, MeteorologicalPrecipitation.values()),
            buffer.readFloat(),
            buffer.readFloat(),
            buffer.readFloat(),
            buffer.readFloat(),
            buffer.readFloat(),
            buffer.readFloat(),
            buffer.readFloat(),
            buffer.readFloat(),
            buffer.readFloat(),
            buffer.readFloat(),
            buffer.readFloat(),
            buffer.readFloat(),
            buffer.readFloat(),
            readEnum(buffer, ResolvedPrecipitation.values()),
            readEnum(buffer, GameplaySeverity.values()),
            buffer.readFloat(),
            buffer.readBoolean(),
            buffer.readLong()
        );
    }

    static WeatherAuthorityMode readAuthorityMode(RegistryFriendlyByteBuf buffer) {
        return readEnum(buffer, WeatherAuthorityMode.values());
    }
}
