package com.ukgeo.worldgen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Velocity-aware predictive chunk tickets: when a player moves fast (elytra / creative fly),
 * request a temporary corridor of chunks ahead so generation stays ahead of travel without
 * permanently raising view-distance for everyone.
 *
 * <p>Uses vanilla {@link TicketType} with a non-zero timeout so tickets expire if not refreshed.
 * Tickets are refreshed while the player stays above the speed threshold and explicitly removed
 * when speed drops or the corridor moves.
 */
public final class TravelChunkPrefetch {
    private static final boolean ENABLED = parseBool("ukgeo.enableTravelPrefetch", true);
    private static final boolean DEBUG = Boolean.getBoolean("ukgeo.debugTravelPrefetch");
    private static final double MIN_SPEED_BPS = Double.parseDouble(System.getProperty("ukgeo.prefetchMinSpeedBps", "40"));
    private static final double LOOK_AHEAD_SECONDS = Double.parseDouble(System.getProperty("ukgeo.prefetchLookAheadSeconds", "2.5"));
    private static final int HALF_WIDTH_CHUNKS = Math.max(0, Integer.getInteger("ukgeo.prefetchHalfWidthChunks", 1));
    private static final int MAX_TICKETS = Math.max(8, Integer.getInteger("ukgeo.prefetchMaxTickets", 128));
    private static final int INTERVAL_TICKS = Math.max(1, Integer.getInteger("ukgeo.prefetchIntervalTicks", 10));
    /** Ticket lifetime in DistanceManager purge ticks; refreshed on each successful prefetch pass. */
    private static final int TICKET_TIMEOUT_TICKS = Math.max(INTERVAL_TICKS * 3, 40);
    private static final int REGION_RADIUS = 0;

    private static final boolean ENABLE_HIGH_SPEED_VD = Boolean.getBoolean("ukgeo.enableHighSpeedViewDistance");
    private static final double HIGH_SPEED_VD_BPS = Double.parseDouble(System.getProperty("ukgeo.highSpeedViewDistanceBps", "80"));
    private static final int HIGH_SPEED_VIEW_DISTANCE = Math.max(2, Integer.getInteger("ukgeo.highSpeedViewDistance", 6));

    private static final TicketType<ChunkPos> PREFETCH_TICKET = TicketType.create(
        "ukgeo_prefetch",
        Comparator.comparingLong(ChunkPos::toLong),
        TICKET_TIMEOUT_TICKS
    );

    private static final Map<UUID, PlayerSample> LAST_SAMPLES = new HashMap<>();
    /** Active prefetch tickets for the overworld (chunk long → ChunkPos). */
    private static final Map<Long, ChunkPos> ACTIVE_TICKETS = new HashMap<>();

    private static boolean loggedEnabled;
    private static int tickCounter;
    private static double lastPlayerSpeedBps;
    private static String lastPlayerName = "-";
    private static Integer savedViewDistance;
    private static boolean highSpeedVdActive;

    private TravelChunkPrefetch() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (!ENABLED) {
            return;
        }
        if (!loggedEnabled) {
            loggedEnabled = true;
            UkGeoMod.LOGGER.info(
                "UKGeo travel prefetch enabled (minSpeed={} b/s, lookAhead={}s, halfWidth={}, maxTickets={}, interval={}t, ticketTimeout={}t, highSpeedVD={})",
                MIN_SPEED_BPS,
                LOOK_AHEAD_SECONDS,
                HALF_WIDTH_CHUNKS,
                MAX_TICKETS,
                INTERVAL_TICKS,
                TICKET_TIMEOUT_TICKS,
                ENABLE_HIGH_SPEED_VD
            );
        }

        tickCounter++;
        if (tickCounter % INTERVAL_TICKS != 0) {
            return;
        }

        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null || !isUkGeoWorld(overworld)) {
            releaseAll(overworld);
            restoreViewDistanceIfNeeded(server);
            return;
        }

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        Set<Long> desired = new LinkedHashSet<>();
        boolean anyFast = false;
        boolean anyHighSpeedVd = false;
        double maxSpeed = 0.0;
        String maxSpeedName = "-";

        for (ServerPlayer player : players) {
            if (player.level() != overworld) {
                LAST_SAMPLES.remove(player.getUUID());
                continue;
            }

            double x = player.getX();
            double z = player.getZ();
            long gameTime = overworld.getGameTime();
            PlayerSample previous = LAST_SAMPLES.put(player.getUUID(), new PlayerSample(x, z, gameTime));
            if (previous == null) {
                continue;
            }

            long dt = Math.max(1L, gameTime - previous.gameTime());
            double dx = x - previous.x();
            double dz = z - previous.z();
            double dist = Math.sqrt(dx * dx + dz * dz);
            double speedBps = dist * 20.0 / dt;

            if (speedBps > maxSpeed) {
                maxSpeed = speedBps;
                maxSpeedName = player.getGameProfile().getName();
            }

            if (speedBps >= HIGH_SPEED_VD_BPS) {
                anyHighSpeedVd = true;
            }

            if (speedBps < MIN_SPEED_BPS || dist < 1.0e-3) {
                continue;
            }

            anyFast = true;
            double inv = 1.0 / dist;
            double dirX = dx * inv;
            double dirZ = dz * inv;
            addCorridor(desired, player.chunkPosition(), dirX, dirZ, speedBps);
        }

        lastPlayerSpeedBps = maxSpeed;
        lastPlayerName = maxSpeedName;

        // Drop samples for players who left.
        if (LAST_SAMPLES.size() > players.size() + 4) {
            Set<UUID> online = new HashSet<>();
            for (ServerPlayer player : players) {
                online.add(player.getUUID());
            }
            LAST_SAMPLES.keySet().retainAll(online);
        }

        if (!anyFast) {
            releaseAll(overworld);
        } else {
            syncTickets(overworld, desired);
        }

        if (ENABLE_HIGH_SPEED_VD) {
            updateHighSpeedViewDistance(server, anyHighSpeedVd);
        } else {
            restoreViewDistanceIfNeeded(server);
        }

        if (DEBUG) {
            UkGeoMod.LOGGER.info(
                "UKGeo prefetch tick: maxSpeed={}/s player={} activeTickets={} desired={} highSpeedVd={}",
                String.format("%.1f", lastPlayerSpeedBps),
                lastPlayerName,
                ACTIVE_TICKETS.size(),
                desired.size(),
                highSpeedVdActive
            );
        }
    }

    private static void addCorridor(Set<Long> desired, ChunkPos origin, double dirX, double dirZ, double speedBps) {
        int lengthChunks = Math.max(1, (int) Math.ceil(speedBps * LOOK_AHEAD_SECONDS / 16.0));
        double perpX = -dirZ;
        double perpZ = dirX;

        List<ChunkCandidate> candidates = new ArrayList<>(lengthChunks * (HALF_WIDTH_CHUNKS * 2 + 1));
        for (int along = 1; along <= lengthChunks; along++) {
            double centerX = origin.x + dirX * along;
            double centerZ = origin.z + dirZ * along;
            for (int across = -HALF_WIDTH_CHUNKS; across <= HALF_WIDTH_CHUNKS; across++) {
                int chunkX = (int) Math.round(centerX + perpX * across);
                int chunkZ = (int) Math.round(centerZ + perpZ * across);
                if (chunkX == origin.x && chunkZ == origin.z) {
                    continue;
                }
                long key = ChunkPos.asLong(chunkX, chunkZ);
                candidates.add(new ChunkCandidate(key, along, Math.abs(across)));
            }
        }

        candidates.sort(Comparator
            .comparingInt(ChunkCandidate::along)
            .thenComparingInt(ChunkCandidate::acrossAbs));

        int remaining = MAX_TICKETS - desired.size();
        for (ChunkCandidate candidate : candidates) {
            if (remaining <= 0) {
                break;
            }
            if (desired.add(candidate.key())) {
                remaining--;
            }
        }
    }

    private static void syncTickets(ServerLevel level, Set<Long> desired) {
        // Cap global outstanding set.
        if (desired.size() > MAX_TICKETS) {
            Iterator<Long> it = desired.iterator();
            int keep = 0;
            Set<Long> capped = new LinkedHashSet<>();
            while (it.hasNext() && keep < MAX_TICKETS) {
                capped.add(it.next());
                keep++;
            }
            desired = capped;
        }

        // Remove tickets no longer in the corridor.
        List<Long> toRemove = new ArrayList<>();
        for (Long key : ACTIVE_TICKETS.keySet()) {
            if (!desired.contains(key)) {
                toRemove.add(key);
            }
        }
        for (Long key : toRemove) {
            ChunkPos pos = ACTIVE_TICKETS.remove(key);
            if (pos != null) {
                level.getChunkSource().removeRegionTicket(PREFETCH_TICKET, pos, REGION_RADIUS, pos);
            }
        }

        // Add / refresh tickets still desired (re-add refreshes createdTick via DistanceManager).
        for (Long key : desired) {
            ChunkPos pos = ACTIVE_TICKETS.get(key);
            if (pos == null) {
                pos = new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key));
                ACTIVE_TICKETS.put(key, pos);
            }
            level.getChunkSource().addRegionTicket(PREFETCH_TICKET, pos, REGION_RADIUS, pos);
        }
    }

    private static void releaseAll(ServerLevel level) {
        if (ACTIVE_TICKETS.isEmpty()) {
            return;
        }
        if (level != null) {
            for (ChunkPos pos : ACTIVE_TICKETS.values()) {
                level.getChunkSource().removeRegionTicket(PREFETCH_TICKET, pos, REGION_RADIUS, pos);
            }
        }
        ACTIVE_TICKETS.clear();
        if (DEBUG) {
            UkGeoMod.LOGGER.info("UKGeo prefetch released all tickets (speed below threshold or no ukgeo world)");
        }
    }

    private static void updateHighSpeedViewDistance(MinecraftServer server, boolean anyHighSpeed) {
        PlayerList players = server.getPlayerList();
        if (anyHighSpeed) {
            if (!highSpeedVdActive) {
                savedViewDistance = players.getViewDistance();
                players.setViewDistance(HIGH_SPEED_VIEW_DISTANCE);
                highSpeedVdActive = true;
                UkGeoMod.LOGGER.info(
                    "UKGeo high-speed view-distance hint: {} → {} (affects all players; disable with -Dukgeo.enableHighSpeedViewDistance=false)",
                    savedViewDistance,
                    HIGH_SPEED_VIEW_DISTANCE
                );
            }
        } else {
            restoreViewDistanceIfNeeded(server);
        }
    }

    private static void restoreViewDistanceIfNeeded(MinecraftServer server) {
        if (!highSpeedVdActive || savedViewDistance == null) {
            highSpeedVdActive = false;
            savedViewDistance = null;
            return;
        }
        server.getPlayerList().setViewDistance(savedViewDistance);
        UkGeoMod.LOGGER.info("UKGeo high-speed view-distance restored to {}", savedViewDistance);
        highSpeedVdActive = false;
        savedViewDistance = null;
    }

    private static boolean isUkGeoWorld(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        return generator instanceof UkGeoChunkGenerator;
    }

    static String statusLine() {
        return "prefetch enabled=" + ENABLED
            + " lastSpeedBps=" + String.format("%.1f", lastPlayerSpeedBps)
            + " lastPlayer=" + lastPlayerName
            + " activeTickets=" + ACTIVE_TICKETS.size()
            + " maxTickets=" + MAX_TICKETS
            + " minSpeedBps=" + MIN_SPEED_BPS
            + " lookAheadSeconds=" + LOOK_AHEAD_SECONDS
            + " halfWidth=" + HALF_WIDTH_CHUNKS
            + " intervalTicks=" + INTERVAL_TICKS
            + " ticketTimeoutTicks=" + TICKET_TIMEOUT_TICKS
            + " highSpeedVD=" + ENABLE_HIGH_SPEED_VD
            + (highSpeedVdActive ? " (active→" + HIGH_SPEED_VIEW_DISTANCE + ")" : "");
    }

    private static boolean parseBool(String key, boolean defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    private record PlayerSample(double x, double z, long gameTime) {
    }

    private record ChunkCandidate(long key, int along, int acrossAbs) {
    }
}
