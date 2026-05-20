package com.foodspoilage.spoilage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record FoodStackData(
    long creationTime,
    long spoilStartTime,
    long expiryTime,
    long durationMillis,
    double freshness,
    double complexity,
    String classification
) {
    public static final Codec<FoodStackData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.LONG.fieldOf("creation_time").forGetter(FoodStackData::creationTime),
        Codec.LONG.fieldOf("spoil_start_time").forGetter(FoodStackData::spoilStartTime),
        Codec.LONG.fieldOf("expiry_time").forGetter(FoodStackData::expiryTime),
        Codec.LONG.fieldOf("duration_millis").forGetter(FoodStackData::durationMillis),
        Codec.DOUBLE.optionalFieldOf("freshness", 1.0D).forGetter(FoodStackData::freshness),
        Codec.DOUBLE.optionalFieldOf("complexity", 0.0D).forGetter(FoodStackData::complexity),
        Codec.STRING.optionalFieldOf("classification", FoodClassification.SIMPLE.name()).forGetter(FoodStackData::classification)
    ).apply(instance, FoodStackData::new));

    public static FoodStackData create(long now, FoodProfile profile) {
        long duration = Math.max(1_000L, profile.durationMillis());
        return new FoodStackData(now, now, now + duration, duration, 1.0D, profile.complexity(), profile.classification().name());
    }

    public double freshnessAt(long now) {
        if (now <= this.spoilStartTime) {
            return 1.0D;
        }
        if (now >= this.expiryTime) {
            return 0.0D;
        }
        double remaining = this.expiryTime - now;
        double total = Math.max(1.0D, this.expiryTime - this.spoilStartTime);
        return clamp(remaining / total);
    }

    public SpoilageStage stageAt(long now) {
        double value = freshnessAt(now);
        if (value > 0.66D) {
            return SpoilageStage.FRESH;
        }
        if (value > 0.33D) {
            return SpoilageStage.STALE;
        }
        if (value > 0.0D) {
            return SpoilageStage.SPOILED;
        }
        return SpoilageStage.ROTTEN;
    }

    public FoodStackData refreshed(long now) {
        return new FoodStackData(this.creationTime, this.spoilStartTime, this.expiryTime, this.durationMillis, freshnessAt(now), this.complexity, this.classification);
    }

    public FoodStackData averagedWith(FoodStackData other, int thisCount, int otherCount, long now, FoodProfile profile) {
        int total = Math.max(1, thisCount + otherCount);
        double mergedFreshness = (freshnessAt(now) * thisCount + other.freshnessAt(now) * otherCount) / total;
        long duration = Math.max(1_000L, profile.durationMillis());
        long expiry = now + Math.round(duration * clamp(mergedFreshness));
        return new FoodStackData(Math.min(this.creationTime, other.creationTime), now, expiry, duration, clamp(mergedFreshness), profile.complexity(), profile.classification().name());
    }

    public FoodStackData withFreshness(double freshness, long now, FoodProfile profile) {
        double clamped = clamp(freshness);
        long duration = Math.max(1_000L, profile.durationMillis());
        return new FoodStackData(this.creationTime, now, now + Math.round(duration * clamped), duration, clamped, profile.complexity(), profile.classification().name());
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
