package com.ukgeo.realtimelocalisedweather.api;

import com.ukgeo.realtimelocalisedweather.weather.LocalWeatherState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public interface RegionalWeatherAccess {
    LocalWeatherState getWeatherAt(ServerLevel level, BlockPos position);

    boolean isPrecipitatingAt(ServerLevel level, BlockPos position);

    boolean isRainingAt(ServerLevel level, BlockPos position);

    boolean isSnowingAt(ServerLevel level, BlockPos position);

    boolean isThunderingAt(ServerLevel level, BlockPos position);
}
