package com.ukgeo.realtimelocalisedweather;

import com.ukgeo.realtimelocalisedweather.network.ProtocolVersions;
import com.ukgeo.realtimelocalisedweather.network.WeatherProtocolPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class PayloadAndProtocolTest {
    @Test
    void weatherProtocolPayloadCodecRoundTrips() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        WeatherProtocolPayload payload = new WeatherProtocolPayload("rlw-1", "1.0.0");

        WeatherProtocolPayload.STREAM_CODEC.encode(buffer, payload);
        WeatherProtocolPayload decoded = WeatherProtocolPayload.STREAM_CODEC.decode(buffer);

        assertEquals(payload, decoded);
    }

    @Test
    void protocolVersionMismatchIsRejected() {
        assertTrue(ProtocolVersions.isCompatible("rlw-1"));
        assertFalse(ProtocolVersions.isCompatible("rlw-0"));
        assertFalse(ProtocolVersions.isCompatible("other"));
    }
}
