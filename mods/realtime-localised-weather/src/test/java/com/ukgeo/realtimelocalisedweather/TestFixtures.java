package com.ukgeo.realtimelocalisedweather;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class TestFixtures {
    private TestFixtures() {
    }

    public static String resource(String path) {
        try (InputStream inputStream = TestFixtures.class.getResourceAsStream(path)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("Missing fixture " + path);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
    }
}
