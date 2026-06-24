package com.zenyfh.animalhunger.jade;

import com.zenyfh.animalhunger.AnimalHunger;
import com.zenyfh.animalhunger.config.AnimalHungerConfig;
import com.zenyfh.animalhunger.hunger.AnimalHungerData;
import com.zenyfh.animalhunger.hunger.AnimalHungerEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.TooltipPosition;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

@WailaPlugin
public final class AnimalHungerJadePlugin implements IWailaPlugin {
    /**
     * Deliberately uses a fresh provider id instead of the old "animal_hunger" id.
     * Some earlier test builds registered that id with Jade; using a new id avoids
     * stale client-side Jade config entries silently keeping the provider disabled.
     */
    public static final ResourceLocation HUNGER = ResourceLocation.fromNamespaceAndPath(AnimalHunger.MOD_ID, "hunger");

    private static final AnimalProvider ANIMAL_PROVIDER = new AnimalProvider();

    private static final String DATA_ROOT = "AnimalHunger";
    private static final String DATA_SUPPORTED = "Supported";
    private static final String DATA_HUNGER = "Hunger";
    private static final String DATA_MAX = "MaxHunger";

    // Keys used by the previous attempt. Kept as a client-side fallback so users can
    // hot-swap jars while Jade still has an old server-data packet cached.
    private static final String LEGACY_DATA_SUPPORTED = "AnimalHungerSupported";
    private static final String LEGACY_DATA_HUNGER = "AnimalHungerValue";
    private static final String LEGACY_DATA_MAX = "AnimalHungerMax";

    @Override
    public void register(IWailaCommonRegistration registration) {
        // Register at Entity.class and then filter ourselves. This avoids relying on
        // Jade's class-hierarchy lookup for modded animal subclasses while still only
        // writing data for entities that Animal Hunger actually supports.
        registration.registerEntityDataProvider(ANIMAL_PROVIDER, Entity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        // registerEntityComponent automatically creates the Jade plugin toggle.
        registration.registerEntityComponent(ANIMAL_PROVIDER, Entity.class);
    }

    private static final class AnimalProvider implements IEntityComponentProvider, IServerDataProvider<EntityAccessor> {
        @Override
        public ResourceLocation getUid() {
            return HUNGER;
        }

        @Override
        public boolean enabledByDefault() {
            return true;
        }

        @Override
        public int getDefaultPriority() {
            // > 5000 keeps the line visible when Jade's overlay is collapsed/lite.
            return TooltipPosition.TAIL;
        }

        @Override
        public boolean shouldRequestData(EntityAccessor accessor) {
            return AnimalHungerConfig.ENABLE_JADE_INTEGRATION.get()
                    && accessor.getEntity() instanceof LivingEntity living
                    && AnimalHungerEvents.isSupported(living);
        }

        @Override
        public void appendServerData(CompoundTag data, EntityAccessor accessor) {
            Entity entity = accessor.getEntity();
            if (!(entity instanceof LivingEntity living) || !AnimalHungerConfig.ENABLE_JADE_INTEGRATION.get()) {
                return;
            }
            appendHungerData(data, living);
        }

        @Override
        public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
            if (!AnimalHungerConfig.ENABLE_JADE_INTEGRATION.get()) {
                return;
            }

            HungerSnapshot snapshot = readSyncedData(accessor.getServerData());
            if (snapshot == null && accessor.getEntity() instanceof LivingEntity living) {
                // Usually this is empty on the logical client, but it is a harmless
                // fallback and makes the provider work in any environment where the
                // entity persistent data has already been mirrored client-side.
                snapshot = readEntityData(living);
            }
            if (snapshot == null || !snapshot.supported()) {
                return;
            }

            int max = Math.max(1, snapshot.max());
            int hunger = Math.max(0, Math.min(max, snapshot.hunger()));
            Component text = hunger <= 3
                    ? Component.translatable("jade.animalhunger.hunger_starving", hunger, max)
                    : Component.translatable("jade.animalhunger.hunger", hunger, max);
            tooltip.add(text);
        }
    }

    private static void appendHungerData(CompoundTag data, LivingEntity living) {
        if (!AnimalHungerConfig.HUNGER_ENABLED.get()) {
            return;
        }
        if (!AnimalHungerEvents.isSupported(living)) {
            debugJade(living, "unsupported");
            return;
        }

        // The mod stores hunger in LivingEntity#getPersistentData() under the
        // "animalhunger" compound. In /data this appears under NeoForgeData.
        CompoundTag hungerRoot = AnimalHungerData.getOrCreate(living, living.level().getGameTime());
        if (!hasUsableHungerData(hungerRoot)) {
            debugJade(living, "no usable animalhunger persistent data");
            return;
        }

        int max = AnimalHungerData.maxHunger();
        int hunger = Math.max(0, Math.min(max, hungerRoot.getInt(AnimalHungerData.HUNGER)));

        CompoundTag synced = new CompoundTag();
        synced.putBoolean(DATA_SUPPORTED, true);
        synced.putInt(DATA_HUNGER, hunger);
        synced.putInt(DATA_MAX, max);
        data.put(DATA_ROOT, synced);

        debugJade(living, hunger + "/" + max);
    }

    private static HungerSnapshot readSyncedData(CompoundTag data) {
        if (data.contains(DATA_ROOT)) {
            CompoundTag root = data.getCompound(DATA_ROOT);
            if (root.getBoolean(DATA_SUPPORTED)) {
                return new HungerSnapshot(true, root.getInt(DATA_HUNGER), root.getInt(DATA_MAX));
            }
        }

        if (data.getBoolean(LEGACY_DATA_SUPPORTED)) {
            return new HungerSnapshot(true, data.getInt(LEGACY_DATA_HUNGER), data.getInt(LEGACY_DATA_MAX));
        }

        return null;
    }

    private static HungerSnapshot readEntityData(LivingEntity living) {
        if (!AnimalHungerEvents.isSupported(living)) {
            return null;
        }
        CompoundTag hungerRoot = AnimalHungerData.get(living);
        if (!hasUsableHungerData(hungerRoot)) {
            return null;
        }
        int max = AnimalHungerData.maxHunger();
        return new HungerSnapshot(true, hungerRoot.getInt(AnimalHungerData.HUNGER), max);
    }

    private static boolean hasUsableHungerData(CompoundTag hungerRoot) {
        return hungerRoot.getBoolean(AnimalHungerData.INITIALIZED) || hungerRoot.contains(AnimalHungerData.HUNGER);
    }


    private static void debugJade(LivingEntity living, String status) {
        if (AnimalHungerConfig.DEBUG_ANIMAL_HUNGER.get()) {
            AnimalHunger.LOGGER.info("Jade hunger data for {}: {}", AnimalHungerEvents.entityId(living), status);
        }
    }

    private record HungerSnapshot(boolean supported, int hunger, int max) {
    }
}
