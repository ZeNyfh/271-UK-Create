package com.zenyfh.animalhunger.hunger;

import com.zenyfh.animalhunger.config.AnimalHungerConfig;
import com.zenyfh.animalhunger.registry.ModTags;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class AnimalFood {
    private static final Map<ResourceLocation, TagKey<Item>> FOOD_TAGS_BY_ENTITY = new LinkedHashMap<>();

    static {
        put("minecraft", "cow", "cow");
        put("minecraft", "sheep", "sheep");
        put("minecraft", "pig", "pig");
        put("minecraft", "chicken", "chicken");
        put("minecraft", "rabbit", "rabbit");
        put("minecraft", "wolf", "wolf");
        put("minecraft", "fox", "fox");
        put("minecraft", "bat", "bat");
        put("minecraft", "horse", "horse");
        put("minecraft", "donkey", "horse");
        put("minecraft", "mule", "horse");
        put("minecraft", "llama", "horse");
        put("minecraft", "trader_llama", "horse");
        put("minecraft", "goat", "goat");
        put("minecraft", "camel", "camel");
        put("minecraft", "cat", "cat");
        put("minecraft", "ocelot", "cat");
        put("minecraft", "parrot", "parrot");
        put("minecraft", "turtle", "turtle");
        put("minecraft", "bee", "bee");
        put("minecraft", "panda", "panda");
        put("minecraft", "frog", "frog");
        put("minecraft", "armadillo", "armadillo");

        put("wildernature", "deer", "wildernature/deer");
        put("wildernature", "bison", "wildernature/bison");
        put("wildernature", "boar", "wildernature/boar");
        put("wildernature", "dog", "wildernature/dog");
        put("wildernature", "hedgehog", "wildernature/hedgehog");
        put("wildernature", "minisheep", "wildernature/minisheep");
        put("wildernature", "owl", "wildernature/owl");
        put("wildernature", "squirrel", "wildernature/squirrel");
    }

    private AnimalFood() {
    }

    public static boolean canEntityEat(LivingEntity entity, ItemStack stack) {
        if (stack.isEmpty() || !isAnyAnimalFood(stack)) {
            return false;
        }
        TagKey<Item> diet = foodTagFor(entity);
        return diet == ModTags.ANY_ANIMAL_FOOD ? stack.is(ModTags.ANY_ANIMAL_FOOD) : stack.is(diet);
    }

    public static boolean isAnyAnimalFood(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModTags.ANY_ANIMAL_FOOD);
    }

    public static TagKey<Item> foodTagFor(LivingEntity entity) {
        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if ("wildernature".equals(entityId.getNamespace()) && !AnimalHungerConfig.ENABLE_WILDERNATURE_SUPPORT.get()) {
            return ModTags.ANY_ANIMAL_FOOD;
        }
        return FOOD_TAGS_BY_ENTITY.getOrDefault(entityId, ModTags.ANY_ANIMAL_FOOD);
    }

    public static List<Component> supportedAnimalDisplayNames(Container container) {
        // The trough screen uses the same diet tag map as real feeding, so the UI
        // cannot drift away from trough eating or hand-feeding rules.
        List<Component> animals = new ArrayList<>();
        for (Map.Entry<ResourceLocation, TagKey<Item>> entry : FOOD_TAGS_BY_ENTITY.entrySet()) {
            ResourceLocation entityId = entry.getKey();
            if ("wildernature".equals(entityId.getNamespace()) && !AnimalHungerConfig.ENABLE_WILDERNATURE_SUPPORT.get()) {
                continue;
            }
            if (containsFoodFor(container, entry.getValue())) {
                animals.add(Component.translatable("entity." + entityId.getNamespace() + "." + entityId.getPath()));
            }
        }
        return animals;
    }

    private static boolean containsFoodFor(Container container, TagKey<Item> foodTag) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && stack.is(foodTag)) {
                return true;
            }
        }
        return false;
    }

    public static int foodValue(ItemStack stack) {
        if (stack.is(Items.HAY_BLOCK)) return 10;
        if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.GOLDEN_CARROT)) return 8;
        if (stack.is(Items.WHEAT) || stack.is(Items.BREAD) || stack.is(Items.APPLE)
                || stack.is(Items.BEEF) || stack.is(Items.COOKED_BEEF)
                || stack.is(Items.PORKCHOP) || stack.is(Items.COOKED_PORKCHOP)
                || stack.is(Items.MUTTON) || stack.is(Items.COOKED_MUTTON)
                || stack.is(Items.CHICKEN) || stack.is(Items.COOKED_CHICKEN)
                || stack.is(Items.RABBIT) || stack.is(Items.COOKED_RABBIT)
                || stack.is(Items.COD) || stack.is(Items.SALMON) || stack.is(Items.TROPICAL_FISH)) {
            return 5;
        }
        if (stack.is(Items.CARROT) || stack.is(Items.POTATO) || stack.is(Items.BEETROOT)
                || stack.is(Items.MELON_SLICE) || stack.is(Items.PUMPKIN)
                || stack.is(Items.SWEET_BERRIES) || stack.is(Items.GLOW_BERRIES)
                || stack.is(Items.CACTUS) || stack.is(Items.BAMBOO) || stack.is(Items.SEAGRASS)
                || stack.is(Items.SLIME_BALL) || stack.is(Items.MAGMA_CREAM)) {
            return 4;
        }
        return 2;
    }

    private static void put(String namespace, String entity, String foodPath) {
        FOOD_TAGS_BY_ENTITY.put(ResourceLocation.fromNamespaceAndPath(namespace, entity), ModTags.food(foodPath));
    }
}
