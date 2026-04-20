package com.leonimust.client;

import net.minecraft.client.MinecraftClient;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class SpotifyAppConfig {
    private static final String DEFAULT_REDIRECT_URI = "http://127.0.0.1:12589/callback";
    private static final String PLACEHOLDER_CLIENT_ID = "PUT_YOUR_SPOTIFY_CLIENT_ID_HERE";
    private static final File CONFIG_DIR = new File(MinecraftClient.getInstance().runDirectory, "spoticraft");
    private static final File CONFIG_FILE = new File(CONFIG_DIR, "spotify_app.json");

    private SpotifyAppConfig() {
    }

    public static File getConfigFile() {
        return CONFIG_FILE;
    }

    public static Config load() throws IOException {
        ensureConfigExists();
        String content = Files.readString(CONFIG_FILE.toPath(), StandardCharsets.UTF_8);
        JSONObject json = new JSONObject(content);

        String clientId = json.optString("client_id", PLACEHOLDER_CLIENT_ID).trim();
        String redirectUri = json.optString("redirect_uri", DEFAULT_REDIRECT_URI).trim();

        return new Config(clientId, redirectUri);
    }

    private static void ensureConfigExists() throws IOException {
        if (!CONFIG_DIR.exists() && !CONFIG_DIR.mkdirs()) {
            throw new IOException("Unable to create config directory " + CONFIG_DIR.getAbsolutePath());
        }

        if (CONFIG_FILE.exists()) {
            return;
        }

        JSONObject template = new JSONObject();
        template.put("client_id", PLACEHOLDER_CLIENT_ID);
        template.put("redirect_uri", DEFAULT_REDIRECT_URI);
        template.put("notes", "Create your own Spotify app and paste its client_id here. In the Spotify dashboard, add the same redirect_uri exactly.");

        try (FileWriter writer = new FileWriter(CONFIG_FILE, StandardCharsets.UTF_8)) {
            writer.write(template.toString(2));
        }
    }

    public record Config(String clientId, String redirectUri) {
        public boolean hasClientId() {
            return !clientId.isBlank() && !PLACEHOLDER_CLIENT_ID.equals(clientId);
        }
    }
}
