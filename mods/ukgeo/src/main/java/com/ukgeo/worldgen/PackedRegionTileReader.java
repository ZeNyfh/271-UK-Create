package com.ukgeo.worldgen;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

final class PackedRegionTileReader {
    private static final byte[] MAGIC = new byte[] {'U', 'K', 'R', 'G'};
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 28;
    private static final int ENTRY_BYTES = 12;
    private static final int REGION_FILE_CACHE_ENTRIES = Math.max(0, Integer.getInteger("ukgeo.regionFileCacheEntries", 16));
    private static final TileCache<Path, byte[]> REGION_FILE_CACHE = new TileCache<>(Math.max(1, REGION_FILE_CACHE_ENTRIES));

    private PackedRegionTileReader() {
    }

    static byte[] readTile(Path root, TileManifest.LayerStorage storage, int tileSize, int tileX, int tileZ, int bytesPerCell, int defaultValue) throws IOException {
        int tileBytes = tileSize * tileSize * bytesPerCell;
        int regionTiles = Math.max(1, storage.regionTiles());
        int regionX = Math.floorDiv(tileX, regionTiles);
        int regionZ = Math.floorDiv(tileZ, regionTiles);
        int localX = Math.floorMod(tileX, regionTiles);
        int localZ = Math.floorMod(tileZ, regionTiles);
        int index = localZ * regionTiles + localX;
        Path regionPath = root.resolve(storage.regionPath()).resolve("%03d_%03d%s".formatted(regionX, regionZ, storage.regionExtension()));
        if (!Files.exists(regionPath)) {
            return defaultTile(tileBytes, bytesPerCell, defaultValue);
        }
        byte[] file = readRegionFile(regionPath);
        if (file.length < HEADER_BYTES) {
            throw new IOException(regionPath + " is too small to be a UKGeo packed region");
        }
        ByteBuffer buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        for (byte expected : MAGIC) {
            if (buffer.get() != expected) {
                throw new IOException(regionPath + " is not a UKGeo packed region");
            }
        }
        int version = buffer.getInt();
        int storedTileSize = buffer.getInt();
        int storedRegionTiles = buffer.getInt();
        int storedTileBytes = buffer.getInt();
        int storedDefault = buffer.getInt();
        int entryCount = buffer.getInt();
        if (version != VERSION || storedTileSize != tileSize || storedRegionTiles != regionTiles || storedTileBytes != tileBytes) {
            throw new IOException(regionPath + " packed region metadata does not match manifest");
        }
        if (index >= entryCount) {
            return defaultTile(tileBytes, bytesPerCell, storedDefault);
        }
        int entryOffset = HEADER_BYTES + index * ENTRY_BYTES;
        if (file.length < entryOffset + ENTRY_BYTES) {
            throw new IOException(regionPath + " packed region entry table is truncated");
        }
        buffer.position(entryOffset);
        long payloadOffset = buffer.getLong();
        int payloadSize = buffer.getInt();
        if (payloadOffset == 0L || payloadSize == 0) {
            return defaultTile(tileBytes, bytesPerCell, storedDefault);
        }
        if (payloadSize <= 0 || payloadOffset < 0 || payloadOffset + payloadSize > file.length) {
            throw new IOException(regionPath + " packed region payload is invalid");
        }
        byte[] payload = Arrays.copyOfRange(file, (int) payloadOffset, (int) payloadOffset + payloadSize);
        if (payloadSize == tileBytes) {
            return payload;
        }
        return inflate(regionPath, payload, tileBytes);
    }

    private static byte[] readRegionFile(Path regionPath) throws IOException {
        if (REGION_FILE_CACHE_ENTRIES <= 0) {
            return Files.readAllBytes(regionPath);
        }
        return REGION_FILE_CACHE.get(regionPath.toAbsolutePath().normalize(), Files::readAllBytes);
    }

    private static byte[] inflate(Path path, byte[] payload, int expectedSize) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(payload);
            byte[] data = new byte[expectedSize];
            int length = inflater.inflate(data);
            if (length != expectedSize || !inflater.finished()) {
                throw new IOException(path + " packed region payload decompressed to " + length + " bytes, expected " + expectedSize);
            }
            return data;
        } catch (DataFormatException ex) {
            throw new IOException(path + " packed region payload is not valid deflate data", ex);
        } finally {
            inflater.end();
        }
    }

    private static byte[] defaultTile(int tileBytes, int bytesPerCell, int defaultValue) {
        byte[] data = new byte[tileBytes];
        if (bytesPerCell == 1) {
            Arrays.fill(data, (byte) (defaultValue & 0xff));
            return data;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        short value = (short) defaultValue;
        for (int i = 0; i < tileBytes / 2; i++) {
            buffer.putShort(value);
        }
        return data;
    }
}
