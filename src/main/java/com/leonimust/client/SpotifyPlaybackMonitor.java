package com.leonimust.client;

import com.leonimust.client.ui.ImageHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlayingContext;
import se.michaelthelin.spotify.model_objects.specification.Track;

import java.net.URISyntaxException;
import java.util.concurrent.CompletableFuture;

import static com.leonimust.SpotiCraft.LOGGER;

public final class SpotifyPlaybackMonitor {
    private static final long POLL_INTERVAL_MS = 1500;
    private static long lastPollTime;
    private static boolean inFlight;
    private static String lastTrackId;

    private SpotifyPlaybackMonitor() {
    }

    public static void tick(MinecraftClient client) {
        SpotifyHudState.tickProgress();

        if (client.player == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (inFlight || now - lastPollTime < POLL_INTERVAL_MS) {
            return;
        }

        if (TokenStorage.token == null) {
            TokenStorage.loadToken();
        }

        if (TokenStorage.token == null) {
            SpotifyHudState.clear();
            return;
        }

        inFlight = true;
        lastPollTime = now;

        CompletableFuture.runAsync(() -> {
            try {
                TokenStorage.checkIfExpired();
                SpotifyApi api = new SpotifyApi.Builder()
                        .setAccessToken(TokenStorage.token.getString("access_token"))
                        .setRefreshToken(TokenStorage.token.optString("refresh_token", null))
                        .build();

                CurrentlyPlayingContext context = api.getInformationAboutUsersCurrentPlayback().build().execute();
                if (context == null || context.getItem() == null) {
                    lastTrackId = null;
                    SpotifyHudState.clear();
                    return;
                }

                String title = context.getItem().getName();
                int durationMs = context.getItem().getDurationMs();
                int progressMs = context.getProgress_ms();
                boolean playing = context.getIs_playing();
                String artist = "";
                Identifier image = null;
                String trackId = context.getItem().getId();

                if (context.getItem() instanceof Track track) {
                    artist = formatArtists(track);
                    String imageUrl = track.getAlbum() == null || track.getAlbum().getImages() == null || track.getAlbum().getImages().length == 0
                            ? null
                            : track.getAlbum().getImages()[0].getUrl();
                    if (imageUrl != null) {
                        image = ImageHandler.downloadImage(imageUrl);
                    }
                }

                SpotifyHudState.update(title, artist, image, durationMs, progressMs, playing);
                lastTrackId = trackId;
            } catch (Exception e) {
                if (!(e instanceof URISyntaxException)) {
                    LOGGER.debug("Failed to refresh Spotify HUD state", e);
                }
            } finally {
                inFlight = false;
            }
        });
    }

    private static String formatArtists(Track track) {
        if (track.getArtists() == null || track.getArtists().length == 0) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < track.getArtists().length; i++) {
            if (track.getArtists()[i] == null) {
                continue;
            }

            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(track.getArtists()[i].getName());
        }
        return builder.toString();
    }
}
