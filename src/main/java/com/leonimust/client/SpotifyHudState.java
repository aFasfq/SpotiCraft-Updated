package com.leonimust.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

import static com.leonimust.SpotiCraft.MOD_ID;

public final class SpotifyHudState {
    private static final Identifier EMPTY_IMAGE = Identifier.of(MOD_ID, "textures/gui/empty.png");
    private static volatile boolean hasTrack;
    private static volatile boolean playing;
    private static volatile String title = "";
    private static volatile String artist = "";
    private static volatile Identifier image = EMPTY_IMAGE;
    private static volatile int durationMs;
    private static volatile int progressMs;
    private static volatile long lastSyncTime;

    private SpotifyHudState() {
    }

    public static void update(String newTitle, String newArtist, Identifier newImage, int newDurationMs, int newProgressMs, boolean isPlaying) {
        title = newTitle == null ? "" : newTitle;
        artist = newArtist == null ? "" : newArtist;
        image = newImage == null ? EMPTY_IMAGE : newImage;
        durationMs = Math.max(newDurationMs, 0);
        progressMs = Math.max(newProgressMs, 0);
        playing = isPlaying;
        hasTrack = !title.isBlank();
        lastSyncTime = System.currentTimeMillis();
    }

    public static void clear() {
        hasTrack = false;
        playing = false;
        title = "";
        artist = "";
        image = EMPTY_IMAGE;
        durationMs = 0;
        progressMs = 0;
        lastSyncTime = System.currentTimeMillis();
    }

    public static void tickProgress() {
        if (!hasTrack || !playing || durationMs <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        long delta = now - lastSyncTime;
        if (delta <= 0) {
            return;
        }

        progressMs = Math.min(durationMs, progressMs + (int) delta);
        lastSyncTime = now;
    }

    public static boolean hasTrack() {
        return hasTrack;
    }

    public static int getWidth() {
        return 240;
    }

    public static int getHeight() {
        return 56;
    }

    public static boolean contains(double mouseX, double mouseY, int x, int y) {
        return mouseX >= x && mouseX <= x + getWidth() && mouseY >= y && mouseY <= y + getHeight();
    }

    public static void render(DrawContext context, TextRenderer textRenderer, int x, int y, boolean preview) {
        if (!preview && !hasTrack) {
            return;
        }

        int width = getWidth();
        int height = getHeight();
        int background = 0xD9111111;
        int border = 0x66FFFFFF;
        int accent = 0xFF1ED760;
        int secondary = 0xFF9A9A9A;

        context.fill(x, y, x + width, y + height, background);
        context.fill(x, y, x + width, y + 1, border);
        context.fill(x, y + height - 1, x + width, y + height, border);
        context.fill(x, y, x + 1, y + height, border);
        context.fill(x + width - 1, y, x + width, y + height, border);

        Identifier cover = preview ? EMPTY_IMAGE : image;
        context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, cover, x + 6, y + 6, 0, 0, 44, 44, 44, 44);

        String line1 = preview ? "Spotify HUD" : title;
        String line2 = preview ? "Drag to move" : artist;
        String time = preview ? "0:42 / 3:15" : formatTime(progressMs / 1000) + " / " + formatTime(durationMs / 1000);

        context.drawText(textRenderer, line1, x + 56, y + 9, 0xFFFFFFFF, false);
        context.drawText(textRenderer, line2, x + 56, y + 21, secondary, false);
        context.drawText(textRenderer, time, x + 56, y + 34, 0xFFFFFFFF, false);

        int barX = x + 56;
        int barY = y + 47;
        int barWidth = width - 64;
        context.fill(barX, barY, barX + barWidth, barY + 3, 0x44FFFFFF);
        int filled = preview ? Math.max(1, barWidth / 4) : (durationMs <= 0 ? 0 : (int) ((progressMs / (float) durationMs) * barWidth));
        context.fill(barX, barY, barX + filled, barY + 3, accent);
    }

    private static String formatTime(int seconds) {
        int minutes = seconds / 60;
        int remainingSeconds = Math.max(seconds % 60, 0);
        return String.format("%d:%02d", minutes, remainingSeconds);
    }
}
