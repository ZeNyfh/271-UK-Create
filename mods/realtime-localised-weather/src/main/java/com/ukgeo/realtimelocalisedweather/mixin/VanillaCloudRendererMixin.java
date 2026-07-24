package com.ukgeo.realtimelocalisedweather.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ukgeo.realtimelocalisedweather.client.render.LocalisedCloudRenderer;
import com.ukgeo.realtimelocalisedweather.weather.client.ClientWeatherManager;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class VanillaCloudRendererMixin {
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void realtime_localised_weather$renderClouds(PoseStack poseStack, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, float partialTick, double cameraX, double cameraY, double cameraZ, CallbackInfo callbackInfo) {
        if (ClientWeatherManager.shouldReplaceClouds()) {
            LocalisedCloudRenderer.render(poseStack, modelViewMatrix, projectionMatrix, partialTick, cameraX, cameraY, cameraZ);
            callbackInfo.cancel();
        }
    }
}
