package com.ukgeo.realtimelocalisedweather.network;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;

public final class ProtocolVersions {
    private ProtocolVersions() {
    }

    public static boolean isCompatible(String remoteVersion) {
        return RealtimeLocalisedWeatherMod.PROTOCOL_VERSION.equals(remoteVersion);
    }
}
