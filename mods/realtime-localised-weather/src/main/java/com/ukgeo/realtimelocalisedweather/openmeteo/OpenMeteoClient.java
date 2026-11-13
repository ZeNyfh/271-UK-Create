package com.ukgeo.realtimelocalisedweather.openmeteo;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import com.ukgeo.realtimelocalisedweather.config.ServerWeatherConfig;
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
import java.util.concurrent.TimeUnit;

public final class OpenMeteoClient {
    private static final String CURRENT_FIELDS = String.join(",",
        "precipitation",
        "rain",
        "showers",
        "snowfall",
        "weather_code",
        "visibility",
        "temperature_2m",
        "relative_humidity_2m",
        "wind_speed_10m",
        "wind_direction_10m",
        "wind_gusts_10m"
    );
    private static final long INTER_BATCH_DELAY_MS = 250L;

    private final HttpClient httpClient;
    private final Semaphore concurrentRequests;

    public OpenMeteoClient(Executor executor) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(executor)
            .build();
        this.concurrentRequests = new Semaphore(Math.max(1, ServerWeatherConfig.safeInt(ServerWeatherConfig.MAXIMUM_CONCURRENT_REQUESTS, 1)));
    }

    public CompletableFuture<List<OpenMeteoResponse.LocationWeather>> fetchCurrent(List<OpenMeteoResponse.LocationRequest> requests) {
        List<List<OpenMeteoResponse.LocationRequest>> batches = OpenMeteoRequestBatcher.batch(requests);
        // Serialize batches: free-tier Open-Meteo 429s when parallel batch fans-out on cold start.
        CompletableFuture<List<OpenMeteoResponse.LocationWeather>> chain = CompletableFuture.completedFuture(new ArrayList<>());
        for (int i = 0; i < batches.size(); i++) {
            List<OpenMeteoResponse.LocationRequest> batch = batches.get(i);
            boolean delayBefore = i > 0;
            chain = chain.thenCompose(accumulated -> {
                CompletableFuture<Void> gate = delayBefore
                    ? CompletableFuture.runAsync(() -> {
                        try {
                            TimeUnit.MILLISECONDS.sleep(INTER_BATCH_DELAY_MS);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                    }, httpClient.executor().orElse(Runnable::run))
                    : CompletableFuture.completedFuture(null);
                return gate.thenCompose(ignored -> fetchBatch(batch)).thenApply(locations -> {
                    List<OpenMeteoResponse.LocationWeather> merged = new ArrayList<>(accumulated);
                    merged.addAll(locations);
                    return merged;
                });
            });
        }
        return chain;
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
                    String detail = "Open-Meteo HTTP " + response.statusCode();
                    if (response.statusCode() == 429) {
                        detail += " (rate limited)";
                    }
                    throw new IllegalStateException(detail);
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
