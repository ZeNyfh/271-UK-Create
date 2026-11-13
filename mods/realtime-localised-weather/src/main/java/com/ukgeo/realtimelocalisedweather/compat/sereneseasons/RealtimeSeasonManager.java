package com.ukgeo.realtimelocalisedweather.compat.sereneseasons;

import com.ukgeo.realtimelocalisedweather.RealtimeLocalisedWeatherMod;
import com.ukgeo.realtimelocalisedweather.config.ServerWeatherConfig;
import java.time.DateTimeException;
import java.time.ZoneId;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

public final class RealtimeSeasonManager {
    private static final String[] MAJOR_SEASON_NAMES = {"Spring", "Summer", "Autumn", "Winter"};

    private boolean disabledMinecraftCycle;
    private int lastAppliedCycleTicks = Integer.MIN_VALUE;
    private int lastLoggedMajorSeason = Integer.MIN_VALUE;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (!isFeatureActive()) {
            return;
        }
        ServerLevel overworld = event.getServer().overworld();
        ensureAnchor(overworld);
        maybeDisableMinecraftCycle(overworld);
        syncSeason(overworld, true);
    }

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (level.dimension() != Level.OVERWORLD || !isFeatureActive()) {
            return;
        }
        int interval = ServerWeatherConfig.safeInt(ServerWeatherConfig.REALTIME_SEASON_SYNC_INTERVAL_TICKS, 20);
        if (interval <= 0) {
            interval = 20;
        }
        if (level.getGameTime() % interval != 0L) {
            return;
        }
        ensureAnchor(level);
        maybeDisableMinecraftCycle(level);
        syncSeason(level, false);
    }

    private static boolean isFeatureActive() {
        return ServerWeatherConfig.safeBoolean(ServerWeatherConfig.REALTIME_SEASONS_ENABLED, true)
            && SereneSeasonsCompat.isLoaded();
    }

    private void ensureAnchor(ServerLevel level) {
        RealtimeSeasonSavedData data = RealtimeSeasonSavedData.get(level);
        if (data.hasAnchor()) {
            return;
        }
        ZoneId zone = resolveZone();
        long anchor = RealtimeSeasonClock.anchorToStartOfDay(System.currentTimeMillis(), zone);
        data.setAnchorEpochMillis(anchor);
        RealtimeLocalisedWeatherMod.LOGGER.info(
            "Realtime Serene seasons: anchored year start at epoch {} ({})",
            anchor,
            zone
        );
    }

    private void maybeDisableMinecraftCycle(ServerLevel level) {
        if (disabledMinecraftCycle) {
            return;
        }
        if (!ServerWeatherConfig.safeBoolean(ServerWeatherConfig.DISABLE_SERENE_MINECRAFT_SEASON_CYCLE, true)) {
            return;
        }
        if (SereneSeasonsCompat.setDoSeasonCycle(level, false)) {
            disabledMinecraftCycle = true;
            RealtimeLocalisedWeatherMod.LOGGER.info(
                "Realtime Serene seasons: set doSeasonCycle=false so Minecraft-time progression does not fight the wall clock"
            );
        }
    }

    private void syncSeason(ServerLevel level, boolean force) {
        RealtimeSeasonSavedData data = RealtimeSeasonSavedData.get(level);
        if (!data.hasAnchor()) {
            return;
        }
        int daysPerSeason = ServerWeatherConfig.safeInt(ServerWeatherConfig.REALTIME_SEASON_DAYS, 7);
        if (daysPerSeason <= 0) {
            daysPerSeason = 7;
        }
        int cycleDuration = SereneSeasonsCompat.getCycleDurationTicks();
        if (cycleDuration <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long anchor = data.getAnchorEpochMillis();
        int cycleTicks = RealtimeSeasonClock.computeCycleTicks(now, anchor, daysPerSeason, cycleDuration);

        int major = RealtimeSeasonClock.majorSeasonIndex(now, anchor, daysPerSeason);
        if (lastLoggedMajorSeason != Integer.MIN_VALUE && major != lastLoggedMajorSeason) {
            RealtimeLocalisedWeatherMod.LOGGER.info(
                "Realtime Serene seasons: entered {}",
                MAJOR_SEASON_NAMES[major]
            );
        }
        lastLoggedMajorSeason = major;

        if (!force && cycleTicks == lastAppliedCycleTicks) {
            Integer current = SereneSeasonsCompat.getSeasonCycleTicks(level);
            if (current != null && current == cycleTicks) {
                return;
            }
        }
        if (SereneSeasonsCompat.setSeasonCycleTicks(level, cycleTicks)) {
            lastAppliedCycleTicks = cycleTicks;
        }
    }

    private static ZoneId resolveZone() {
        String configured = ServerWeatherConfig.safeString(ServerWeatherConfig.REALTIME_SEASON_TIMEZONE, "Europe/London");
        try {
            return ZoneId.of(configured);
        } catch (DateTimeException exception) {
            RealtimeLocalisedWeatherMod.LOGGER.warn(
                "Invalid realtime_season_timezone '{}', falling back to Europe/London",
                configured
            );
            return ZoneId.of("Europe/London");
        }
    }
}
