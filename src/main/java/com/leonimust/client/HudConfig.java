package com.leonimust.client;

import net.minecraft.client.MinecraftClient;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class HudConfig {
    private static final File CONFIG_DIR = new File(MinecraftClient.getInstance().runDirectory, "spoticraft");
    private static final File CONFIG_FILE = new File(CONFIG_DIR, "hud.json");
    private static Config cachedConfig;

    private HudConfig() {
    }

    public static synchronized Config get() {
        if (cachedConfig == null) {
            cachedConfig = load();
        }

        return cachedConfig;
    }

    public static synchronized void updatePosition(int x, int y) {
        Config current = get();
        cachedConfig = new Config(x, y, current.visible());
        save(cachedConfig);
    }

    public static synchronized void setVisible(boolean visible) {
        Config current = get();
        cachedConfig = new Config(current.x(), current.y(), visible);
        save(cachedConfig);
    }

    private static Config load() {
        try {
            ensureFileExists();
            String content = Files.readString(CONFIG_FILE.toPath(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(content);
            return new Config(
                    json.optInt("x", 20),
                    json.optInt("y", 20),
                    json.optBoolean("visible", true)
            );
        } catch (Exception ignored) {
            return new Config(20, 20, true);
        }
    }

    private static void save(Config config) {
        try {
            ensureDirectoryExists();
            JSONObject json = new JSONObject();
            json.put("x", config.x());
            json.put("y", config.y());
            json.put("visible", config.visible());
            Files.writeString(CONFIG_FILE.toPath(), json.toString(2), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static void ensureFileExists() throws IOException {
        ensureDirectoryExists();
        if (CONFIG_FILE.exists()) {
            return;
        }

        save(new Config(20, 20, true));
    }

    private static void ensureDirectoryExists() throws IOException {
        if (!CONFIG_DIR.exists() && !CONFIG_DIR.mkdirs()) {
            throw new IOException("Unable to create directory " + CONFIG_DIR.getAbsolutePath());
        }
    }

    public record Config(int x, int y, boolean visible) {
    }
}
