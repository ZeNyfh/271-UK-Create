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
    /** National grid is ≤3 dense batches (≤32 pts each); run two HTTP calls in flight. */
    private static final int NATIONAL_BATCH_CONCURRENCY = 2;

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
        if (batches.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        if (batches.size() == 1) {
            return fetchBatch(batches.get(0));
        }
        // Dense national-grid refreshes: parallel under concurrency 2.
        // Serialize + 250ms only when safeguards are on and batches look like small player-tile fallbacks.
        if (shouldSerializeBatches(batches)) {
            return fetchBatchesSerialized(batches);
        }
        return fetchBatchesParallel(batches, NATIONAL_BATCH_CONCURRENCY);
    }

    private static boolean shouldSerializeBatches(List<List<OpenMeteoResponse.LocationRequest>> batches) {
        if (!ServerWeatherConfig.safeBoolean(ServerWeatherConfig.REQUEST_BUDGET_SAFEGUARDS, true)) {
            return false;
        }
        int smallCap = Math.max(1, ServerWeatherConfig.safeInt(ServerWeatherConfig.MAX_TILES_PER_REFRESH, 12));
        return batches.size() > 1 && batches.stream().allMatch(batch -> batch.size() <= smallCap);
    }

    private CompletableFuture<List<OpenMeteoResponse.LocationWeather>> fetchBatchesSerialized(
        List<List<OpenMeteoResponse.LocationRequest>> batches
    ) {
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

    private CompletableFuture<List<OpenMeteoResponse.LocationWeather>> fetchBatchesParallel(
        List<List<OpenMeteoResponse.LocationRequest>> batches,
        int concurrency
    ) {
        // Wave-based so we never block the HttpClient's (often single-thread) executor on a semaphore.
        int limit = Math.max(1, concurrency);
        CompletableFuture<List<OpenMeteoResponse.LocationWeather>> chain = CompletableFuture.completedFuture(new ArrayList<>());
        for (int start = 0; start < batches.size(); start += limit) {
            int end = Math.min(start + limit, batches.size());
            List<List<OpenMeteoResponse.LocationRequest>> wave = batches.subList(start, end);
            chain = chain.thenCompose(accumulated -> {
                List<CompletableFuture<List<OpenMeteoResponse.LocationWeather>>> futures = new ArrayList<>(wave.size());
                for (List<OpenMeteoResponse.LocationRequest> batch : wave) {
                    futures.add(fetchBatchHttp(batch));
                }
                return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
                    List<OpenMeteoResponse.LocationWeather> merged = new ArrayList<>(accumulated);
                    for (CompletableFuture<List<OpenMeteoResponse.LocationWeather>> future : futures) {
                        merged.addAll(future.join());
                    }
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
        return fetchBatchHttp(batch).whenComplete((ignored, throwable) -> concurrentRequests.release());
    }

    private CompletableFuture<List<OpenMeteoResponse.LocationWeather>> fetchBatchHttp(List<OpenMeteoResponse.LocationRequest> batch) {
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
            });
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
