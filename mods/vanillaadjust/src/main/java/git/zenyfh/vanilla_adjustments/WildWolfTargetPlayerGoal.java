package git.zenyfh.vanilla_adjustments;

import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Player;

public class WildWolfTargetPlayerGoal extends NearestAttackableTargetGoal<Player> {
    private final Wolf wolf;

    public WildWolfTargetPlayerGoal(Wolf wolf) {
        super(wolf, Player.class, 10, true, false, target -> !wolf.isTame());
        this.wolf = wolf;
    }

    @Override
    public boolean canUse() {
        return !this.wolf.isTame() && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return !this.wolf.isTame() && super.canContinueToUse();
    }
}
