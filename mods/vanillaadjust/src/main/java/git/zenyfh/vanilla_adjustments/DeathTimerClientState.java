package git.zenyfh.vanilla_adjustments;

public final class DeathTimerClientState {
    private static boolean active;
    private static long targetEndMillis;
    private static int lastDisplayedSeconds = Integer.MIN_VALUE;
    private static String cachedText = "";

    private DeathTimerClientState() {
    }

    public static void receive(DeathTimerSyncPacket packet) {
        if (!packet.active() || packet.remainingTicks() <= 0) {
            clear();
            return;
        }
        active = true;
        targetEndMillis = System.currentTimeMillis() + packet.remainingTicks() * 50L;
        lastDisplayedSeconds = Integer.MIN_VALUE;
        updateText();
    }

    public static void clear() {
        active = false;
        targetEndMillis = 0L;
        lastDisplayedSeconds = Integer.MIN_VALUE;
        cachedText = "";
    }

    public static void clientTick() {
        if (!active) {
            return;
        }
        updateText();
    }

    public static boolean active() {
        return active;
    }

    public static String text() {
        return cachedText;
    }

    private static void updateText() {
        long remainingMillis = Math.max(0L, targetEndMillis - System.currentTimeMillis());
        int remainingSeconds = (int) ((remainingMillis + 999L) / 1000L);
        if (remainingSeconds <= 0) {
            cachedText = "Respawn available";
            lastDisplayedSeconds = 0;
            return;
        }
        if (remainingSeconds == lastDisplayedSeconds) {
            return;
        }
        lastDisplayedSeconds = remainingSeconds;
        cachedText = formatRespawnTime(remainingSeconds);
    }

    private static String formatRespawnTime(int remainingSeconds) {
        if (remainingSeconds <= 0) {
            return "Respawn available";
        }

        int minutes = remainingSeconds / 60;
        int seconds = remainingSeconds % 60;
        if (minutes > 0) {
            return "Respawn available in %dm %ds".formatted(minutes, seconds);
        }
        return "Respawn available in %ds".formatted(seconds);
    }
}
