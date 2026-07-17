package com.ukgeo.realtimelocalisedweather.openmeteo;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import com.ukgeo.realtimelocalisedweather.config.ServerWeatherConfig;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

public final class OpenMeteoClient {
    private static final String CURRENT_FIELDS = String.join(",",
        "precipitation",
        "rain",
        "showers",
        "snowfall",
        "weather_code",
        "cloud_cover",
        "cloud_cover_low",
        "cloud_cover_mid",
        "cloud_cover_high",
        "visibility",
        "temperature_2m",
        "relative_humidity_2m",
        "wind_speed_10m",
        "wind_direction_10m",
        "wind_gusts_10m"
    );

    private final HttpClient httpClient;
    private final Semaphore concurrentRequests;

    public OpenMeteoClient(Executor executor) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(executor)
            .build();
        this.concurrentRequests = new Semaphore(ServerWeatherConfig.safeInt(ServerWeatherConfig.MAXIMUM_CONCURRENT_REQUESTS, 2));
    }

    public CompletableFuture<List<OpenMeteoResponse.LocationWeather>> fetchCurrent(List<OpenMeteoResponse.LocationRequest> requests) {
        List<List<OpenMeteoResponse.LocationRequest>> batches = OpenMeteoRequestBatcher.batch(requests);
        List<CompletableFuture<List<OpenMeteoResponse.LocationWeather>>> futures = new ArrayList<>();
        for (List<OpenMeteoResponse.LocationRequest> batch : batches) {
            futures.add(fetchBatch(batch));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
            .thenApply(ignored -> futures.stream().flatMap(future -> future.join().stream()).toList());
    }

    private CompletableFuture<List<OpenMeteoResponse.LocationWeather>> fetchBatch(List<OpenMeteoResponse.LocationRequest> batch) {
        try {
            concurrentRequests.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(exception);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(buildUrl(batch)))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "RealtimeLocalisedWeather/" + RealtimeLocalisedWeatherMod.MOD_VERSION)
            .GET()
            .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("Open-Meteo HTTP " + response.statusCode());
                }
                return OpenMeteoParser.parse(response.body()).locations();
            })
            .whenComplete((ignored, throwable) -> concurrentRequests.release());
    }

    String buildUrl(List<OpenMeteoResponse.LocationRequest> batch) {
        String latitudes = batch.stream().map(location -> Double.toString(location.latitude())).reduce((left, right) -> left + "," + right).orElseThrow();
        String longitudes = batch.stream().map(location -> Double.toString(location.longitude())).reduce((left, right) -> left + "," + right).orElseThrow();
        String model = ServerWeatherConfig.safeString(ServerWeatherConfig.WEATHER_MODEL, "auto").trim();
        StringBuilder builder = new StringBuilder(ServerWeatherConfig.safeString(ServerWeatherConfig.API_BASE_URL, "https://api.open-meteo.com/v1/forecast"))
            .append("?latitude=").append(encode(latitudes))
            .append("&longitude=").append(encode(longitudes))
            .append("&current=").append(encode(CURRENT_FIELDS))
            .append("&timezone=GMT");
        if (!model.isBlank() && !"auto".equalsIgnoreCase(model)) {
            builder.append("&models=").append(encode(model));
        }
        return builder.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
