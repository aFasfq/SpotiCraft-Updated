package com.leonimust.server;

import com.leonimust.client.SpotifyAppConfig;
import com.leonimust.client.TokenStorage;
import net.minecraft.client.MinecraftClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import static com.leonimust.SpotiCraft.LOGGER;

public class SpotifyAuthHandler {
    private static final String SCOPES = "user-read-playback-state user-modify-playback-state user-read-private playlist-read-private playlist-read-collaborative playlist-modify-private playlist-modify-public user-library-read user-library-modify";
    private static String codeVerifier;

    public static void startAuthFlow() throws Exception {
        SpotifyAppConfig.Config config = requireAppConfig();
        codeVerifier = generateRandomString();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        openAuthUrl(codeChallenge, config);
    }

    private static SpotifyAppConfig.Config requireAppConfig() throws IOException {
        SpotifyAppConfig.Config config = SpotifyAppConfig.load();
        if (!config.hasClientId()) {
            throw new IOException("Missing Spotify client_id. Edit " + SpotifyAppConfig.getConfigFile().getAbsolutePath());
        }

        return config;
    }

    private static String generateRandomString() {
        SecureRandom random = new SecureRandom();
        String possible = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < 64; i++) {
            int index = random.nextInt(possible.length());
            result.append(possible.charAt(index));
        }

        return result.toString();
    }

    private static String generateCodeChallenge(String codeVerifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static String readResponse(HttpURLConnection connection) throws IOException {
        int responseCode = connection.getResponseCode();
        InputStream stream = responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            throw new IOException("Spotify returned HTTP " + responseCode + " without a response body");
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String response = reader.lines().reduce("", (acc, line) -> acc + line);
            if (responseCode >= 400) {
                throw new IOException("Spotify auth failed (" + responseCode + "): " + response);
            }

            return response;
        }
    }

    public static void exchangeCodeForToken(String code) throws IOException, URISyntaxException {
        SpotifyAppConfig.Config config = requireAppConfig();
        String url = "https://accounts.spotify.com/api/token";
        String data = "client_id=" + config.clientId() +
                "&grant_type=authorization_code" +
                "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8) +
                "&redirect_uri=" + URLEncoder.encode(config.redirectUri(), StandardCharsets.UTF_8) +
                "&code_verifier=" + URLEncoder.encode(codeVerifier, StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(data.getBytes(StandardCharsets.UTF_8));
        }

        String response = readResponse(conn);
        JSONObject responseBody = new JSONObject(response);
        TokenStorage.saveToken(
                responseBody.getString("access_token"),
                responseBody.optString("refresh_token", null),
                responseBody.getInt("expires_in")
        );
        MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(null));
    }

    public static boolean refreshAccessToken(String refreshToken) throws IOException, URISyntaxException {
        SpotifyAppConfig.Config config = requireAppConfig();
        String url = "https://accounts.spotify.com/api/token";
        String data = "client_id=" + config.clientId() +
                "&grant_type=refresh_token" +
                "&refresh_token=" + refreshToken;

        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(data.getBytes(StandardCharsets.UTF_8));
        }

        String response = readResponse(conn);
        JSONObject responseBody = new JSONObject(response);
        TokenStorage.saveToken(
                responseBody.getString("access_token"),
                responseBody.optString("refresh_token", refreshToken),
                responseBody.getInt("expires_in")
        );
        return true;
    }

    public static JSONObject getCurrentUserProfile(String accessToken) throws IOException, URISyntaxException {
        HttpURLConnection conn = createAuthorizedGetConnection("https://api.spotify.com/v1/me", accessToken);
        return new JSONObject(readResponse(conn));
    }

    public static JSONObject getSpotifyJson(String url, String accessToken) throws IOException, URISyntaxException {
        HttpURLConnection conn = createAuthorizedGetConnection(url, accessToken);
        conn.setRequestMethod("GET");
        return new JSONObject(readResponse(conn));
    }

    public static JSONArray getSpotifyJsonArray(String url, String accessToken) throws IOException, URISyntaxException {
        HttpURLConnection conn = createAuthorizedGetConnection(url, accessToken);
        return new JSONArray(readResponse(conn));
    }

    private static HttpURLConnection createAuthorizedGetConnection(String url, String accessToken) throws IOException, URISyntaxException {
        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private static void openAuthUrl(String codeChallenge, SpotifyAppConfig.Config config) {
        try {
            int port = new URI(config.redirectUri()).getPort();
            if (port <= 0) {
                throw new IOException("Redirect URI must include an explicit port: " + config.redirectUri());
            }

            CallbackServer.startOrRestart(port);

            String authUrl = "https://accounts.spotify.com/authorize?" +
                    "response_type=code" +
                    "&client_id=" + config.clientId() +
                    "&scope=" + URLEncoder.encode(SCOPES, StandardCharsets.UTF_8) +
                    "&code_challenge_method=S256" +
                    "&code_challenge=" + codeChallenge +
                    "&redirect_uri=" + URLEncoder.encode(config.redirectUri(), StandardCharsets.UTF_8);
            openInBrowser(authUrl);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void openInBrowser(String authUrl) throws Exception {
        URI authUri = new URI(authUrl);
        String osName = System.getProperty("os.name", "");

        if (osName.startsWith("Windows")) {
            try {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", authUrl).start();
                return;
            } catch (IOException e) {
                LOGGER.warn("Windows browser launch via rundll32 failed, falling back to Desktop API", e);
            }
        }

        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(authUri);
            return;
        }

        if (osName.equals("Mac OS X")) {
            new ProcessBuilder("open", authUrl).start();
            return;
        }

        if (osName.contains("Linux")) {
            new ProcessBuilder("xdg-open", authUrl).start();
            return;
        }

        throw new IOException("Unsupported OS for auto-opening Spotify auth URL: " + osName);
    }
}
