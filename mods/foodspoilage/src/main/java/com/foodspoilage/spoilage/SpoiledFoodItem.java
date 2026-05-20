package com.foodspoilage.spoilage;

import com.foodspoilage.config.FoodSpoilageConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class SpoiledFoodItem extends Item {
    public SpoiledFoodItem(Properties properties) {
        super(properties);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        ItemStack result = super.finishUsingItem(stack, level, entity);
        if (level instanceof ServerLevel) {
            if (FoodSpoilageConfig.HUNGER_EFFECT.get()) {
                entity.addEffect(new MobEffectInstance(MobEffects.HUNGER, 20 * 18, 1));
            }
            if (FoodSpoilageConfig.NAUSEA_EFFECT.get()) {
                entity.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 20 * 8, 0));
            }
            if (FoodSpoilageConfig.POISON_EFFECT.get()) {
                entity.addEffect(new MobEffectInstance(MobEffects.POISON, 20 * 6, 0));
            }
        }
        return result;
    }
}
