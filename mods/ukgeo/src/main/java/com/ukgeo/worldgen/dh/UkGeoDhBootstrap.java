package com.ukgeo.worldgen.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.ukgeo.worldgen.UkGeoChunkGenerator;
import com.ukgeo.worldgen.UkGeoMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Sole UKGeo entry that imports Distant Horizons API types.
 * Loaded only after {@code ModList} confirms {@code distanthorizons} is present.
 */
public final class UkGeoDhBootstrap {
    private static volatile boolean hooksRegistered;
    private static volatile boolean registeredLogged;

    private UkGeoDhBootstrap() {
    }

    public static void init() {
        if (hooksRegistered) {
            return;
        }
        synchronized (UkGeoDhBootstrap.class) {
            if (hooksRegistered) {
                return;
            }
            DhApiResult<Void> result = DhApiEventRegister.on(DhApiLevelLoadEvent.class, new LevelLoadHandler());
            if (result != null && !result.success) {
                UkGeoMod.LOGGER.warn(
                    "UKGeo DH LOD generator skipped: failed to bind level-load handler ({})",
                    result.message
                );
                return;
            }
            hooksRegistered = true;
            UkGeoMod.LOGGER.info(
                "UKGeo DH LOD generator hooks ready (API {}.{}.{}, mod {}); waiting for UkGeo levels",
                DhApi.getApiMajorVersion(),
                DhApi.getApiMinorVersion(),
                DhApi.getApiPatchVersion(),
                DhApi.getModVersion()
            );
        }
    }

    static void tryRegisterForLevel(IDhApiLevelWrapper levelWrapper) {
        Object mcLevel = levelWrapper.getWrappedMcObject();
        if (!(mcLevel instanceof ServerLevel serverLevel)) {
            return;
        }
        ChunkGenerator generator = serverLevel.getChunkSource().getGenerator();
        if (!(generator instanceof UkGeoChunkGenerator ukGeo)) {
            return;
        }
        UkGeoDhLodWorldGenerator worldGenerator = new UkGeoDhLodWorldGenerator(levelWrapper, ukGeo);
        DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(levelWrapper, worldGenerator);
        if (result != null && !result.success) {
            UkGeoMod.LOGGER.warn(
                "UKGeo DH LOD generator skipped for {}: {}",
                levelWrapper.getDhIdentifier(),
                result.message
            );
            return;
        }
        if (!registeredLogged) {
            registeredLogged = true;
            UkGeoMod.LOGGER.info("UKGeo DH LOD generator registered");
        } else {
            UkGeoMod.LOGGER.info("UKGeo DH LOD generator registered for {}", levelWrapper.getDhIdentifier());
        }
    }

    private static final class LevelLoadHandler extends DhApiLevelLoadEvent {
        @Override
        public void onLevelLoad(DhApiEventParam<EventParam> input) {
            tryRegisterForLevel(input.value.levelWrapper);
        }
    }
}
