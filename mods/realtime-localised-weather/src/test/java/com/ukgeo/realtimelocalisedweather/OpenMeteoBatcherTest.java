package com.ukgeo.realtimelocalisedweather;

import com.ukgeo.realtimelocalisedweather.openmeteo.OpenMeteoRequestBatcher;
import com.ukgeo.realtimelocalisedweather.openmeteo.OpenMeteoResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class OpenMeteoBatcherTest {
    @Test
    void requestBatcherSplitsByUrlBudget() {
        List<OpenMeteoResponse.LocationRequest> requests = java.util.stream.IntStream.range(0, 10)
            .mapToObj(index -> new OpenMeteoResponse.LocationRequest(Integer.toString(index), 55.0 + index / 100.0, -3.0 - index / 100.0))
            .toList();

        List<List<OpenMeteoResponse.LocationRequest>> batches = OpenMeteoRequestBatcher.batch(requests, 4, 320);

        assertTrue(batches.size() >= 3);
        assertEquals(10, batches.stream().mapToInt(List::size).sum());
        assertTrue(batches.stream().allMatch(batch -> batch.size() <= 4));
    }
}
