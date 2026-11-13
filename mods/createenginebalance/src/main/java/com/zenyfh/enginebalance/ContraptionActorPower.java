package com.zenyfh.enginebalance;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public final class ContraptionActorPower {
    private static final String NERF_ACC_KEY = "enginebalanceNerfAcc";

    private ContraptionActorPower() {
    }

    public static boolean shouldNerf(Object movementContext) {
        return EngineBalanceConfig.shouldNerfFreeContraptionActors(getContraption(movementContext));
    }

    public static Object getContraption(Object movementContext) {
        if (movementContext == null) {
            return null;
        }
        try {
            return movementContext.getClass().getField("contraption").get(movementContext);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * Count enabled actors (MovementContext.disabled == false) on a contraption via getActors().
     */
    public static int countEnabledActors(Object contraption) {
        if (contraption == null) {
            return 0;
        }
        try {
            Method getActors = contraption.getClass().getMethod("getActors");
            Object actorsObj = getActors.invoke(contraption);
            if (!(actorsObj instanceof List<?> actors)) {
                return 0;
            }
            int count = 0;
            for (Object pair : actors) {
                if (pair == null) {
                    continue;
                }
                Object context = getPairRight(pair);
                if (context == null) {
                    continue;
                }
                if (isDisabled(context)) {
                    continue;
                }
                count++;
            }
            return count;
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /**
     * Sum enabled actors across all carriages of a train (Mounted/Carriage nerf types only).
     */
    public static int countEnabledActorsOnTrain(Object train) {
        if (train == null) {
            return 0;
        }
        try {
            Field carriagesField = train.getClass().getField("carriages");
            Object carriagesObj = carriagesField.get(train);
            if (!(carriagesObj instanceof List<?> carriages)) {
                return 0;
            }
            int total = 0;
            for (Object carriage : carriages) {
                if (carriage == null) {
                    continue;
                }
                Method anyAvailable = carriage.getClass().getMethod("anyAvailableEntity");
                Object entity = anyAvailable.invoke(carriage);
                if (entity == null) {
                    continue;
                }
                Method getContraption = entity.getClass().getMethod("getContraption");
                Object contraption = getContraption.invoke(entity);
                if (!EngineBalanceConfig.shouldNerfFreeContraptionActors(contraption)) {
                    continue;
                }
                total += countEnabledActors(contraption);
            }
            return total;
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /** Vehicle motion leftover: clamp(pow(f, N), 0, 1). N=0 → 1. */
    public static double machineLoadScaleForContraption(Object contraption) {
        if (!EngineBalanceConfig.shouldNerfFreeContraptionActors(contraption)) {
            return 1.0D;
        }
        int n = countEnabledActors(contraption);
        return machineLoadScale(n);
    }

    /** Train-wide motion leftover from sum of enabled actors across carriages. */
    public static double machineLoadScaleForTrain(Object train) {
        if (train == null || !EngineBalanceConfig.DISABLE_FREE_CONTRAPTION_ACTORS.get()) {
            return 1.0D;
        }
        int n = countEnabledActorsOnTrain(train);
        return machineLoadScale(n);
    }

    private static double machineLoadScale(int n) {
        if (n <= 0) {
            return 1.0D;
        }
        double f = EngineBalanceConfig.freeContraptionActorSpeedFactor();
        if (f >= 1.0D) {
            return 1.0D;
        }
        double scale = Math.pow(f, n);
        if (scale < 0.0D) {
            return 0.0D;
        }
        if (scale > 1.0D) {
            return 1.0D;
        }
        return scale;
    }

    /**
     * Per-actor work scale: machineLoadScale / N (or 1 if N=0).
     * Used by breaking speed + visit/tick throttle.
     */
    public static float speedFactor(Object movementContext) {
        Object contraption = getContraption(movementContext);
        if (!EngineBalanceConfig.shouldNerfFreeContraptionActors(contraption)) {
            return 1f;
        }
        int n = countEnabledActors(contraption);
        if (n <= 0) {
            return 1f;
        }
        double load = machineLoadScale(n);
        return (float) (load / n);
    }

    public static float scaleBreakingSpeed(Object context, float speed) {
        float factor = speedFactor(context);
        return factor < 1f ? speed * factor : speed;
    }

    /**
     * Throttle visit/tick actors (harvester, deployer, plough/roller visit).
     * Accumulates cumulative per-actor speedFactor each call; skips until accumulator reaches 1.0.
     * @return true if this call should be skipped (cancelled)
     */
    public static boolean shouldThrottleSkip(Object movementContext) {
        if (!shouldNerf(movementContext)) {
            return false;
        }
        float factor = speedFactor(movementContext);
        if (factor >= 1f) {
            return false;
        }
        Object data = getDataTag(movementContext);
        if (data == null) {
            return false;
        }
        float acc = getFloat(data, NERF_ACC_KEY) + factor;
        if (acc < 1f) {
            putFloat(data, NERF_ACC_KEY, acc);
            return true;
        }
        putFloat(data, NERF_ACC_KEY, acc - 1f);
        return false;
    }

    /** Motion scale for a MovementContext or AbstractContraptionEntity. */
    public static double motionScale(Object movementContextOrContraptionEntity) {
        if (movementContextOrContraptionEntity == null) {
            return 1.0D;
        }
        Object contraption = getContraption(movementContextOrContraptionEntity);
        if (contraption == null) {
            try {
                Method getContraption = movementContextOrContraptionEntity.getClass().getMethod("getContraption");
                contraption = getContraption.invoke(movementContextOrContraptionEntity);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return machineLoadScaleForContraption(contraption);
    }

    private static Object getPairRight(Object pair) {
        try {
            Field right = pair.getClass().getField("right");
            return right.get(pair);
        } catch (ReflectiveOperationException e) {
            try {
                Method getRight = pair.getClass().getMethod("getRight");
                return getRight.invoke(pair);
            } catch (ReflectiveOperationException e2) {
                return null;
            }
        }
    }

    private static boolean isDisabled(Object movementContext) {
        try {
            Field disabled = movementContext.getClass().getField("disabled");
            Object value = disabled.get(movementContext);
            return value instanceof Boolean b && b;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static Object getDataTag(Object movementContext) {
        try {
            Field dataField = movementContext.getClass().getField("data");
            return dataField.get(movementContext);
        } catch (ReflectiveOperationException e) {
            try {
                Method getData = movementContext.getClass().getMethod("getData");
                return getData.invoke(movementContext);
            } catch (ReflectiveOperationException e2) {
                return null;
            }
        }
    }

    private static float getFloat(Object compoundTag, String key) {
        try {
            Method m = compoundTag.getClass().getMethod("getFloat", String.class);
            Object result = m.invoke(compoundTag, key);
            if (result instanceof Float f) {
                return f;
            }
            if (result instanceof Number n) {
                return n.floatValue();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            Method m = compoundTag.getClass().getMethod("getFloat", String.class, float.class);
            Object result = m.invoke(compoundTag, key, 0f);
            if (result instanceof Float f) {
                return f;
            }
            if (result instanceof Number n) {
                return n.floatValue();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return 0f;
    }

    private static void putFloat(Object compoundTag, String key, float value) {
        try {
            Method m = compoundTag.getClass().getMethod("putFloat", String.class, float.class);
            m.invoke(compoundTag, key, value);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
