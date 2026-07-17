package com.ukgeo.realtimelocalisedweather.openmeteo;

import java.util.ArrayList;
import java.util.List;

public final class OpenMeteoRequestBatcher {
    private static final int DEFAULT_MAX_URL_LENGTH = 1800;
    private static final int DEFAULT_MAX_LOCATIONS = 32;

    private OpenMeteoRequestBatcher() {
    }

    public static List<List<OpenMeteoResponse.LocationRequest>> batch(List<OpenMeteoResponse.LocationRequest> locations) {
        return batch(locations, DEFAULT_MAX_LOCATIONS, DEFAULT_MAX_URL_LENGTH);
    }

    public static List<List<OpenMeteoResponse.LocationRequest>> batch(List<OpenMeteoResponse.LocationRequest> locations, int maxLocations, int maxUrlLength) {
        List<List<OpenMeteoResponse.LocationRequest>> result = new ArrayList<>();
        List<OpenMeteoResponse.LocationRequest> current = new ArrayList<>();
        int currentUrlLength = 256;
        for (OpenMeteoResponse.LocationRequest location : locations) {
            int nextLength = currentUrlLength + coordinateLength(location);
            if (!current.isEmpty() && (current.size() >= maxLocations || nextLength > maxUrlLength)) {
                result.add(List.copyOf(current));
                current.clear();
                currentUrlLength = 256;
            }
            current.add(location);
            currentUrlLength += coordinateLength(location);
        }
        if (!current.isEmpty()) {
            result.add(List.copyOf(current));
        }
        return result;
    }

    private static int coordinateLength(OpenMeteoResponse.LocationRequest location) {
        return Double.toString(location.latitude()).length()
            + Double.toString(location.longitude()).length()
            + 2;
    }
}
