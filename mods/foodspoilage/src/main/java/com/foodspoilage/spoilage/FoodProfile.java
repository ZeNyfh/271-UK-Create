package com.foodspoilage.spoilage;

public record FoodProfile(FoodClassification classification, double complexity, long durationMillis) {
}
