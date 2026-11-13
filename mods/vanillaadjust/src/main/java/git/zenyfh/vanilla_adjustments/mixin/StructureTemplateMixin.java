package git.zenyfh.vanilla_adjustments.mixin;

import git.zenyfh.vanilla_adjustments.DecorativeCopperStructureProcessor;
import git.zenyfh.vanilla_adjustments.VanillaAdjustConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructureTemplate.class)
public abstract class StructureTemplateMixin {
    @Inject(method = "placeInWorld", at = @At("HEAD"))
    private void vanillaadjust$addDecorativeCopperProcessor(
            ServerLevelAccessor serverLevel,
            BlockPos offset,
            BlockPos pos,
            StructurePlaceSettings settings,
            RandomSource random,
            int flags,
            CallbackInfoReturnable<Boolean> callbackInfo) {
        if (!VanillaAdjustConfig.REPLACE_STRUCTURE_COPPER_WITH_DECORATIVE.get()) {
            return;
        }
        if (!settings.getProcessors().contains(DecorativeCopperStructureProcessor.INSTANCE)) {
            settings.addProcessor(DecorativeCopperStructureProcessor.INSTANCE);
        }
    }
}
