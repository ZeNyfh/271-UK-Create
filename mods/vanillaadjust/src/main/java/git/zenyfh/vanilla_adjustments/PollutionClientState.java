package git.zenyfh.vanilla_adjustments;

public final class PollutionClientState {
    private static float targetPollution;
    private static float displayPollution;

    private PollutionClientState() {
    }

    public static void receive(float pollution) {
        targetPollution = Math.max(0.0F, pollution);
    }

    public static float displayPollution() {
        displayPollution += (targetPollution - displayPollution) * 0.05F;
        return displayPollution;
    }

    public static float currentDisplayPollution() {
        return displayPollution;
    }
}
