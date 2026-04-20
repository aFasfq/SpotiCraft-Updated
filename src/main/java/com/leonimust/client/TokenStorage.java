package com.leonimust.client;

import com.leonimust.client.ui.SpotifyScreen;
import com.leonimust.server.SpotifyAuthHandler;
import net.minecraft.client.MinecraftClient;
import org.json.JSONObject;
import se.michaelthelin.spotify.SpotifyApi;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;

import static com.leonimust.SpotiCraft.LOGGER;

public class TokenStorage {
    private static final MinecraftClient MC = MinecraftClient.getInstance();
    private static final File tokenFile = new File(MC.runDirectory, "spoticraft/spotify_tokens.json");
    private static final File DIR = new File(MC.runDirectory, "spoticraft");

    static {
        if (!DIR.exists()) {
            boolean result = DIR.mkdirs();
            if (!result) {
                throw new RuntimeException("Unable to create directory " + DIR);
            }
        }
    }

    public static JSONObject token;

    // Save the tokens in JSON format
    public static void saveToken(String accessToken, String refreshToken, int expiresIn) {
        String effectiveRefreshToken = refreshToken;
        if ((effectiveRefreshToken == null || effectiveRefreshToken.isBlank()) && token != null) {
            effectiveRefreshToken = token.optString("refresh_token", null);
        }

        JSONObject tokenJson = new JSONObject();
        tokenJson.put("access_token", accessToken);
        if (effectiveRefreshToken != null && !effectiveRefreshToken.isBlank()) {
            tokenJson.put("refresh_token", effectiveRefreshToken);
        }
        tokenJson.put("expires_in", expiresIn);
        // removed 250 out of 3600 from expire so we avoid having an expired token
        tokenJson.put("timestamp", System.currentTimeMillis() + (expiresIn - 250) * 1000L);

        try (FileWriter writer = new FileWriter(tokenFile)) {
            writer.write(tokenJson.toString());
            token = tokenJson;
            LOGGER.info("Saved token to {}", tokenFile.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to write token to file : " + e.getMessage());
        }
    }

    // Load the tokens from the JSON file
    public static void loadToken() {
        try {
            if (tokenFile.exists()) {
                String content = new String(Files.readAllBytes(tokenFile.toPath()));
                token = new JSONObject(content);
            } else {
                token = null;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read token from file : " + e.getMessage());
        }
    }

    public static void checkIfExpired() throws IOException, URISyntaxException {
        synchronized (TokenStorage.class) { // Synchronize to avoid concurrent modifications
            if (token == null) {
                loadToken();
            }

            if (token != null && token.getLong("timestamp") <= System.currentTimeMillis()) {
                String refreshToken = token.optString("refresh_token", "");
                if (refreshToken.isBlank()) {
                    throw new IOException("Missing refresh token");
                }

                // Refresh the token and wait for completion
                boolean refreshed = SpotifyAuthHandler.refreshAccessToken(refreshToken);
                if (!refreshed) {
                    throw new IOException("Failed to refresh the token");
                }

                SpotifyScreen.spotifyApi = new SpotifyApi.Builder()
                        .setAccessToken(token.getString("access_token"))
                        .setRefreshToken(token.optString("refresh_token", null))
                        .build();
            }
        }
    }
}
