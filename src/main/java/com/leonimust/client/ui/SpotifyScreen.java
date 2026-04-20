package com.leonimust.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.leonimust.client.SpotifyHudState;
import com.leonimust.client.TokenStorage;
import com.leonimust.server.SpotifyAuthHandler;
import com.neovisionaries.i18n.CountryCode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.apache.hc.core5.http.ParseException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.lwjgl.glfw.GLFW;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.exceptions.detailed.ForbiddenException;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlayingContext;
import se.michaelthelin.spotify.model_objects.specification.*;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.leonimust.SpotiCraft.MOD_ID;
import static com.leonimust.SpotiCraft.LOGGER;

public class SpotifyScreen extends Screen {
    private static SpotifyScreen instance;

    private DrawContext graphics;
    private int totalDurationMs;
    private int currentProgressMs;
    private boolean musicPlaying = false;
    private long lastUpdateTime;
    private boolean shuffleState = false;
    private boolean likedSong = false;

    private ImageButton playStopButton;
    private ImageButton shuffleButton;
    private ImageButton repeatButton;
    private ImageButton nextButton;
    private ImageButton previousButton;
    private ImageButton goBackButton;
    private ImageButton goForwardButton;
    private ImageButton homeButton;
    private ImageButton likeButton;
    private ButtonWidget loginButton;
    private ButtonWidget changeAccountButton;
    private ButtonWidget tokenRefreshButton;
    private ButtonWidget playlistTargetButton;
    private ButtonWidget playlistAddButton;
    private ButtonWidget playlistRemoveButton;

    public static SpotifyApi spotifyApi;

    private int barWidth;
    private final int barHeight = 4;

    private boolean userPremium = false;
    private CountryCode userCountryCode;
    private String currentUserId;

    private boolean tokenExpired = false;
    private boolean authorizationIssue = false;

    private TextManager textManager;
    private Timer tempMessageTimer;
    private Timer progressUpdateTimer;

    Identifier PLAY_TEXTURE = Identifier.of(MOD_ID, "textures/gui/play.png");
    Identifier PAUSE_TEXTURE = Identifier.of(MOD_ID, "textures/gui/pause.png");
    Identifier EMPTY_IMAGE = Identifier.of(MOD_ID, "textures/gui/empty.png");
    Identifier LIKE_TEXTURE = Identifier.of(MOD_ID, "textures/gui/like_icon.png");
    Identifier LIKED_TEXTURE = Identifier.of(MOD_ID, "textures/gui/liked_icon.png");
    Identifier SHUFFLE = Identifier.of(MOD_ID, "textures/gui/shuffle.png");
    Identifier SHUFFLE_ENABLE = Identifier.of(MOD_ID, "textures/gui/shuffle_enable.png");
    Identifier REPEAT = Identifier.of(MOD_ID, "textures/gui/repeat.png");
    Identifier REPEAT_ENABLE = Identifier.of(MOD_ID, "textures/gui/repeat_enable.png");
    Identifier REPEAT_ONE = Identifier.of(MOD_ID, "textures/gui/repeat_1.png");

    private final String[] trackList = {"off", "context", "track"};
    private int trackIndex = 0;

    private Identifier musicImage; // Holds the texture for the current music cover
    private String artistName;
    private String musicName;

    private final HashMap<String, JSONObject> trackCache = new HashMap<>();

    private int volumeBarWidth;
    private final int volumeBarHeight = 4;
    private int currentVolume = 50;

    private ItemScrollPanel playlistPanel;
    private ItemScrollPanel mainPanel;

    private TextFieldWidget searchInput;

    private final List<Item> playlistItems = new ArrayList<>();
    private List<Item> mainItems = new ArrayList<>();
    private final List<PlaylistSelection> editablePlaylists = new ArrayList<>();

    // save all actions so user can go back
    private final List<List<Item>> itemCache = new ArrayList<>();
    private final List<List<Item>> itemCacheForward = new ArrayList<>();

    private String currentTrackId;
    private String currentTrackUri;
    private int selectedPlaylistIndex = 0;

    int imageWidth = 30;
    int imageHeight = 30;

    private TextRenderer textRenderer;

    public SpotifyScreen() {
        super(Text.translatable("gui.spoticraft.spotify_player"));
        instance = this;
    }

    private record PlaylistSelection(String id, String name) {}

    @Override
    protected void init() {
        textRenderer = MinecraftClient.getInstance().textRenderer;
        this.barWidth = this.width / 3 - 10;
        this.volumeBarWidth = this.width / 8;
        this.textManager = new TextManager(textRenderer);

        if (TokenStorage.token == null) {
            return;
        }

        if (checkIfExpired()) {return;}

        // Initialize the Spotify API client
        spotifyApi = new SpotifyApi.Builder()
                .setAccessToken(TokenStorage.token.getString("access_token"))
                .setRefreshToken(TokenStorage.token.optString("refresh_token", null))
                .build();

        JSONObject userProfile;
        try {
            userProfile = SpotifyAuthHandler.getCurrentUserProfile(TokenStorage.token.getString("access_token"));
        } catch (Exception e) {
            LOGGER.error("Failed to load Spotify profile", e);
            authorizationIssue = true;
            tokenExpired = false;
            return;
        }

        userPremium = "premium".equalsIgnoreCase(userProfile.optString("product"));
        userCountryCode = CountryCode.getByCode(userProfile.optString("country"));
        currentUserId = userProfile.optString("id", "");

        // Sync playback state only after profile/auth state is known to be valid.
        syncData();
        startProgressTimer();
    }

    public static SpotifyScreen getInstance() {
        return instance;
    }

    private void startProgressTimer() {
        if (progressUpdateTimer != null) {
            progressUpdateTimer.cancel();
        }

        progressUpdateTimer = new Timer("SpotiCraft-Progress", true);
        progressUpdateTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (musicPlaying) {
                    long currentTime = System.currentTimeMillis();
                    int elapsedMs = (int) (currentTime - lastUpdateTime);
                    currentProgressMs = Math.min(currentProgressMs + elapsedMs, totalDurationMs);
                    lastUpdateTime = currentTime;

                    if (currentProgressMs >= totalDurationMs) {
                        scheduleSyncData(500);
                    }
                }
            }
        }, 0, 1000);
    }

    public void scheduleSyncData(long delayMillis) {
        Timer timer = new Timer("SpotiCraft-Sync", true);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                MinecraftClient client = MinecraftClient.getInstance();
                client.execute(() -> {
                    if (instance == SpotifyScreen.this) {
                        syncData();
                    }
                });
                timer.cancel();
            }
        }, delayMillis);
    }

    private void setButtonVisibility(ButtonWidget button, boolean visible) {
        if (button == null) {
            return;
        }

        button.visible = visible;
        button.active = visible;
    }

    private void hideAuthButtons() {
        setButtonVisibility(loginButton, false);
        setButtonVisibility(changeAccountButton, false);
        setButtonVisibility(tokenRefreshButton, false);
    }

    private int opaqueColor(int color) {
        return (color & 0xFF000000) == 0 ? color | 0xFF000000 : color;
    }

    private <T> T joinFuture(CompletableFuture<T> future, String actionDescription) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.error("Failed to {}", actionDescription, cause);
            return null;
        }
    }

    private boolean hasEditablePlaylist() {
        return !editablePlaylists.isEmpty();
    }

    private PlaylistSelection getSelectedPlaylist() {
        if (!hasEditablePlaylist()) {
            return null;
        }

        if (selectedPlaylistIndex >= editablePlaylists.size()) {
            selectedPlaylistIndex = 0;
        }

        return editablePlaylists.get(selectedPlaylistIndex);
    }

    private String buildPlaylistTargetLabel() {
        PlaylistSelection selection = getSelectedPlaylist();
        if (selection == null) {
            return Text.translatable("gui.spoticraft.no_playlist_available").getString();
        }

        return Text.translatable("gui.spoticraft.playlist_target").getString() + ": " + resizeText(selection.name(), 80);
    }

    private void cyclePlaylistTarget() {
        if (!hasEditablePlaylist()) {
            ShowTempMessage("gui.spoticraft.no_playlist_available");
            return;
        }

        selectedPlaylistIndex = (selectedPlaylistIndex + 1) % editablePlaylists.size();
        updatePlaylistManagementButtons();
    }

    private void updatePlaylistManagementButtons() {
        boolean hasTrack = currentTrackUri != null && !currentTrackUri.isBlank();
        boolean hasPlaylist = hasEditablePlaylist();

        if (playlistTargetButton != null) {
            playlistTargetButton.setMessage(Text.literal(buildPlaylistTargetLabel()));
            playlistTargetButton.active = hasPlaylist;
            playlistTargetButton.visible = true;
        }

        if (playlistAddButton != null) {
            playlistAddButton.active = hasTrack && hasPlaylist;
            playlistAddButton.visible = true;
        }

        if (playlistRemoveButton != null) {
            playlistRemoveButton.active = hasTrack && hasPlaylist;
            playlistRemoveButton.visible = true;
        }
    }

    private void addCurrentTrackToSelectedPlaylist() {
        PlaylistSelection playlist = getSelectedPlaylist();
        if (playlist == null || currentTrackUri == null || currentTrackUri.isBlank()) {
            ShowTempMessage("gui.spoticraft.no_playlist_available");
            return;
        }

        try {
            if (checkIfExpired()) {return;}

            JsonArray uris = new JsonArray();
            uris.add(currentTrackUri);
            spotifyApi.addItemsToPlaylist(playlist.id(), uris).build().execute();
            ShowTempMessage("gui.spoticraft.track_added_to_playlist");
        } catch (IOException | ParseException | SpotifyWebApiException e) {
            ShowTempMessage(e.getMessage());
        }
    }

    private void removeCurrentTrackFromSelectedPlaylist() {
        PlaylistSelection playlist = getSelectedPlaylist();
        if (playlist == null || currentTrackUri == null || currentTrackUri.isBlank()) {
            ShowTempMessage("gui.spoticraft.no_playlist_available");
            return;
        }

        try {
            if (checkIfExpired()) {return;}

            JsonObject track = new JsonObject();
            track.addProperty("uri", currentTrackUri);
            JsonArray tracks = new JsonArray();
            tracks.add(track);
            spotifyApi.removeItemsFromPlaylist(playlist.id(), tracks).build().execute();
            ShowTempMessage("gui.spoticraft.track_removed_from_playlist");
        } catch (IOException | ParseException | SpotifyWebApiException e) {
            ShowTempMessage(e.getMessage());
        }
    }

    @Override
    public void render(DrawContext guiGraphics, int mouseX, int mouseY, float partialTicks) {
        // Draw the background
        guiGraphics.fill(0, 0, this.width, this.height, 0xff080404);

        graphics = guiGraphics;

        // Render all buttons and other widgets
        super.render(guiGraphics, mouseX, mouseY, partialTicks);

        // if no token is found that means the user is not logged
        if (TokenStorage.token == null) {
            loginScreen();
        } else {
            if (authorizationIssue) {
                authorizationIssueScreen();
            } else if (!userPremium && !tokenExpired) {
                // check if the user has premium or not
                noPremium();
            } else if (!tokenExpired) {
                try {
                    mainScreen();
                } catch (IOException | ParseException | SpotifyWebApiException e) {
                    LOGGER.error("Failed to render Spotify screen", e);
                    ShowTempMessage("gui.spoticraft.sync_error");
                }
            } else {
                tokenExpiredScreen();
            }
        }
    }

    // screens
    private void loginScreen() {
        setButtonVisibility(playlistTargetButton, false);
        setButtonVisibility(playlistAddButton, false);
        setButtonVisibility(playlistRemoveButton, false);
        setButtonVisibility(changeAccountButton, false);
        setButtonVisibility(tokenRefreshButton, false);
        this.drawCenteredString(graphics, Text.translatable("gui.spoticraft.not_logged").getString(), this.width / 2, 20, 16777215);
        if (loginButton == null) {
            loginButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("chat.link.open"), button ->
                    {
                        try {
                            SpotifyAuthHandler.startAuthFlow();
                        } catch (Exception e) {
                            LOGGER.error("Failed to start Spotify auth flow", e);
                            ShowTempMessage(e.getMessage() == null ? "gui.spoticraft.sync_error" : e.getMessage());
                        }
                    }
            ).dimensions(this.width / 2 - 50, this.height / 2, 100, 20).build());
        }

        setButtonVisibility(loginButton, true);
    }

    private void mainScreen() throws IOException, ParseException, SpotifyWebApiException {
        hideAuthButtons();

        if (playlistPanel == null) {
            playlistPanel = new ItemScrollPanel(this.width / 3,this.height - 64, 5, 20);
            // useful for first init
            playlistPanel.setInfo(playlistItems);
            // don't move this line down, if minecraft keep refreshing this panel and that the items list changes
            // it will crash the game
            this.addDrawableChild(playlistPanel);
        }

        if (mainPanel == null) {
            mainPanel = new ItemScrollPanel(this.width - this.width / 3 - 15,this.height - 65, this.width/3+10, 20);
            // useful for first init
            mainPanel.setInfo(mainItems);
            // don't move this line down, if minecraft keep refreshing this panel and that the items list changes
            // it will crash the game
            this.addDrawableChild(mainPanel);
        }

        if (musicImage != null) {

            ImageHandler.drawImage(graphics, musicImage, this.height, imageHeight, imageWidth);

            //title
            graphics.drawText(textRenderer, musicName, imageWidth + 10, this.height - imageWidth + 2, 0xFFFFFFFF, false);
            //artist name
            graphics.drawText(textRenderer, artistName, imageWidth + 10, this.height - imageWidth + 12, 0xFF8A8A8A, false);
        }

        //Minecraft ImageButton is shit and doesn't work ;_; thanks for the 4 hours of lost time xD
        if (playStopButton == null) {
            playStopButton = new ImageButton(
                    this.width / 2 - 8,
                    this.height - 35,
                    15, // Button width
                    15, // Button height
                    musicPlaying ? PAUSE_TEXTURE : PLAY_TEXTURE,  // Use stop texture if playing, otherwise play texture
                    15, // Full texture width
                    15, // Full texture height
                    musicPlaying ? "gui.spoticraft.pause" : "gui.spoticraft.play",
                    button -> {
                        toggleMusicPlayback();
                    } // Toggle playback on click
            );
            this.addDrawableChild(playStopButton);
        }

        // Update the texture if the music playing state has changed
        playStopButton.setTexture(musicPlaying ? PAUSE_TEXTURE : PLAY_TEXTURE);

        // Update the tooltip if the music playing state has changed
        playStopButton.setTooltip(musicPlaying ? "gui.spoticraft.pause" : "gui.spoticraft.play");

        if (nextButton == null) {
            nextButton = new ImageButton(
                    this.width / 2 + 15,
                    this.height - 35,
                    13, // Button width
                    13, // Button height
                    Identifier.of(MOD_ID, "textures/gui/next.png"),  // Use stop texture if playing, otherwise play texture
                    13, // Full texture width
                    13, // Full texture height
                    "gui.spoticraft.next",
                    button -> {
                        try {
                            if (checkIfExpired()) {return;}

                            spotifyApi.skipUsersPlaybackToNextTrack().build().execute();
                            syncDataWithDelay();
                        } catch (IOException | SpotifyWebApiException | ParseException e) {
                            ShowTempMessage("gui.spoticraft.no_device");
                        }
                    }
            );
            this.addDrawableChild(nextButton);
        }

        if (previousButton == null) {
            previousButton = new ImageButton(
                    this.width / 2 - 30,
                    this.height - 35,
                    13, // Button width
                    13, // Button height
                    Identifier.of(MOD_ID, "textures/gui/previous.png"),  // Use stop texture if playing, otherwise play texture
                    13, // Full texture width
                    13, // Full texture height
                    "gui.spoticraft.previous",
                    button -> {
                        try {
                            if (checkIfExpired()) {return;}

                            spotifyApi.skipUsersPlaybackToPreviousTrack().build().execute();
                            syncDataWithDelay();
                        } catch (IOException | SpotifyWebApiException | ParseException e) {
                            ShowTempMessage("gui.spoticraft.no_device");
                        }
                    }
            );

            previousButton.setActive(!shuffleState);

            this.addDrawableChild(previousButton);
        }

        if (shuffleButton == null) {
            shuffleButton = new ImageButton(
                    this.width / 2 - 50,
                    this.height - 35,
                    13, // Button width
                    13, // Button height
                    shuffleState ? SHUFFLE_ENABLE : SHUFFLE,  // Use stop texture if playing, otherwise play texture
                    13, // Full texture width
                    13, // Full texture height
                    shuffleState ? "gui.spoticraft.disable_shuffle" : "gui.spoticraft.enable_shuffle",
                    button -> {
                        try {
                            if (checkIfExpired()) {return;}

                            spotifyApi.toggleShuffleForUsersPlayback(!shuffleState).build().execute();
                            shuffleState = !shuffleState;
                            shuffleButton.setTooltip(shuffleState ? "gui.spoticraft.disable_shuffle" : "gui.spoticraft.enable_shuffle");
                            shuffleButton.setTexture(shuffleState ? SHUFFLE_ENABLE : SHUFFLE);

                            previousButton.setActive(!shuffleState);
                        } catch (IOException | SpotifyWebApiException | ParseException e) {
                            ShowTempMessage("gui.spoticraft.no_device");
                        }
                    }
            );
            this.addDrawableChild(shuffleButton);
        }

        if (repeatButton == null) {
            repeatButton = new ImageButton(
                    this.width / 2 + 35,
                    this.height - 35,
                    13, // Button width
                    13, // Button height
                    trackIndex == 0 ? REPEAT : trackIndex == 1 ? REPEAT_ENABLE : REPEAT_ONE,  // Use stop texture if playing, otherwise play texture
                    13, // Full texture width
                    13, // Full texture height
                    trackIndex == 0 ? "gui.spoticraft.enable_repeat" : trackIndex == 1 ? "gui.spoticraft.enable_repeat_one" : "gui.spoticraft.disable_repeat",
                    button -> {
                        try {
                            if (checkIfExpired()) {return;}

                            trackIndex = (trackIndex + 1) % trackList.length;
                            spotifyApi.setRepeatModeOnUsersPlayback(trackList[trackIndex]).build().execute();
                            repeatButton.setTooltip(trackIndex == 0 ? "gui.spoticraft.enable_repeat" : trackIndex == 1 ? "gui.spoticraft.enable_repeat_one" : "gui.spoticraft.disable_repeat");
                            repeatButton.setTexture(trackIndex == 0 ? REPEAT : trackIndex == 1 ? REPEAT_ENABLE : REPEAT_ONE);
                        } catch (IOException | SpotifyWebApiException | ParseException e) {
                            ShowTempMessage("gui.spoticraft.no_device");
                        }
                    }
            );
            this.addDrawableChild(repeatButton);
        }

        if (searchInput == null) {
            searchInput = new TextFieldWidget(textRenderer, this.width/2 - this.width/8, 3, this.width/4, 15, Text.empty());
            this.addDrawableChild(searchInput);
        }

        if (playlistTargetButton == null) {
            playlistTargetButton = this.addDrawableChild(ButtonWidget.builder(Text.literal(buildPlaylistTargetLabel()), button -> cyclePlaylistTarget())
                    .dimensions(25, 3, 135, 15)
                    .build());
        }

        if (playlistAddButton == null) {
            playlistAddButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.spoticraft.add_to_playlist"), button -> addCurrentTrackToSelectedPlaylist())
                    .dimensions(165, 3, 60, 15)
                    .build());
        }

        if (playlistRemoveButton == null) {
            playlistRemoveButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.spoticraft.remove_from_playlist"), button -> removeCurrentTrackFromSelectedPlaylist())
                    .dimensions(230, 3, 75, 15)
                    .build());
        }

        if (goBackButton == null) {
            goBackButton = new ImageButton(
                    this.width/2 - this.width/6 + 6,
                    4,
                    13, // Button width
                    13, // Button height
                    Identifier.of(MOD_ID, "textures/gui/go_back.png"),  // Use stop texture if playing, otherwise play texture
                    13, // Full texture width
                    13, // Full texture height
                    "gui.spoticraft.go_back",
                    button -> goBack()
            );
            this.addDrawableChild(goBackButton);
        }

        if (goForwardButton == null) {
            goForwardButton = new ImageButton(
                    this.width/2 + this.width/6 - 19,
                    4,
                    13, // Button width
                    13, // Button height
                    Identifier.of(MOD_ID, "textures/gui/go_forward.png"),  // Use stop texture if playing, otherwise play texture
                    13, // Full texture width
                    13, // Full texture height
                    "gui.spoticraft.go_forward",
                    button -> goForward()
            );
            this.addDrawableChild(goForwardButton);
        }

        if (homeButton == null) {
            homeButton = new ImageButton(
                    5,
                    3,
                    15, // Button width
                    15, // Button height
                    Identifier.of(MOD_ID, "textures/gui/home.png"),  // Use stop texture if playing, otherwise play texture
                    15, // Full texture width
                    15, // Full texture height
                    "gui.spoticraft.home",
                    button -> {
                        try {
                            showHomePage();
                        } catch (IOException | ParseException | SpotifyWebApiException e) {
                            LOGGER.error("Failed to open Spotify home page", e);
                            ShowTempMessage("gui.spoticraft.sync_error");
                        }
                    }
            );
            this.addDrawableChild(homeButton);
        }

        if (mainItems.isEmpty()) {
            try {
                this.showHomePage();
                //once it's finished save the main page
                saveLastAction();
            } catch (IOException | ParseException | SpotifyWebApiException e) {
                LOGGER.error("Failed to load Spotify home page", e);
                populateHomeFallback();
            }
        }

        if (playlistItems.isEmpty()) {
            try {
                showUserPlaylists();
            } catch (IOException | ParseException | SpotifyWebApiException e) {
                LOGGER.error("Failed to load Spotify playlists", e);
                populatePlaylistFallback();
            }
        }

        if (likeButton == null && musicName != null) {
            likeButton = new ImageButton(
                    imageWidth + 10 + textRenderer.getWidth(musicName) + 2,
                    this.height - imageHeight - 1,
                    10, // Button width
                    10, // Button height
                    likedSong ? LIKED_TEXTURE : LIKE_TEXTURE,
                    10, // Full texture width
                    10, // Full texture height
                    likedSong ? "gui.spoticraft.liked" : "gui.spoticraft.like",
                    button -> {
                        try {
                            addOrRemoveLikedSong();
                        } catch (IOException | ParseException | SpotifyWebApiException e) {
                            LOGGER.error("Failed to toggle liked song state", e);
                            ShowTempMessage("gui.spoticraft.sync_error");
                        }
                    }
            );

            this.addDrawableChild(likeButton);
        }

        textManager.drawText(graphics);
        updatePlaylistManagementButtons();

        this.drawMusicControlBar(graphics);
        this.drawVolumeBar(graphics);
    }

    private void noPremium() {
        setButtonVisibility(playlistTargetButton, false);
        setButtonVisibility(playlistAddButton, false);
        setButtonVisibility(playlistRemoveButton, false);
        setButtonVisibility(loginButton, false);
        setButtonVisibility(tokenRefreshButton, false);
        this.drawCenteredString(graphics, Text.translatable("gui.spoticraft.no_premium").getString(), this.width / 2, 20, 16777215);
        this.drawCenteredString(graphics, Text.translatable("gui.spoticraft.no_premium_2").getString(), this.width / 2, 35, 16777215);
        if (changeAccountButton == null) {
            changeAccountButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Open in Browser"), button ->
                    {
                        try {
                            SpotifyAuthHandler.startAuthFlow();
                        } catch (Exception e) {
                            LOGGER.error("Failed to restart Spotify auth flow", e);
                            ShowTempMessage(e.getMessage() == null ? "gui.spoticraft.sync_error" : e.getMessage());
                        }
                    }
            ).dimensions(this.width / 2 - 50, this.height / 2, 100, 20).build());
        }

        setButtonVisibility(changeAccountButton, true);
    }

    private void tokenExpiredScreen() {
        setButtonVisibility(playlistTargetButton, false);
        setButtonVisibility(playlistAddButton, false);
        setButtonVisibility(playlistRemoveButton, false);
        setButtonVisibility(loginButton, false);
        setButtonVisibility(changeAccountButton, false);
        this.drawCenteredString(graphics, Text.translatable("gui.spoticraft.token_expired").getString(), this.width / 2, 20, 16777215);
        if (tokenRefreshButton == null) {
            tokenRefreshButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Open in Browser"), button ->
                    {
                        try {
                            SpotifyAuthHandler.startAuthFlow();
                        } catch (Exception e) {
                            LOGGER.error("Failed to restart Spotify auth flow after token refresh failure", e);
                            ShowTempMessage(e.getMessage() == null ? "gui.spoticraft.sync_error" : e.getMessage());
                        }
                    }
            ).dimensions(this.width / 2 - 50, this.height / 2, 100, 20).build());
        }

        setButtonVisibility(tokenRefreshButton, true);
    }

    private void authorizationIssueScreen() {
        setButtonVisibility(playlistTargetButton, false);
        setButtonVisibility(playlistAddButton, false);
        setButtonVisibility(playlistRemoveButton, false);
        setButtonVisibility(loginButton, false);
        setButtonVisibility(changeAccountButton, false);
        this.drawCenteredString(graphics, Text.translatable("gui.spoticraft.authorization_issue").getString(), this.width / 2, 20, 16777215);
        this.drawCenteredString(graphics, Text.translatable("gui.spoticraft.authorization_issue_2").getString(), this.width / 2, 35, 16777215);
        if (tokenRefreshButton == null) {
            tokenRefreshButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Open in Browser"), button ->
                    {
                        try {
                            authorizationIssue = false;
                            SpotifyAuthHandler.startAuthFlow();
                        } catch (Exception e) {
                            LOGGER.error("Failed to restart Spotify auth flow after authorization issue", e);
                            ShowTempMessage(e.getMessage() == null ? "gui.spoticraft.sync_error" : e.getMessage());
                        }
                    }
            ).dimensions(this.width / 2 - 50, this.height / 2, 100, 20).build());
        }

        setButtonVisibility(tokenRefreshButton, true);
    }

    public void syncDataWithDelay() {
        scheduleSyncData(500);
    }

    // sync
    public void syncData() {
        LOGGER.info("Syncing data");

        try {
            if (checkIfExpired()) {return;}

            CurrentlyPlayingContext context = spotifyApi.getInformationAboutUsersCurrentPlayback().build().execute();

            if (context != null && context.getItem() != null) {
                currentTrackId = context.getItem().getId();
                currentTrackUri = context.getItem().getUri();
                totalDurationMs = context.getItem().getDurationMs();
                currentProgressMs = context.getProgress_ms();
                musicPlaying = context.getIs_playing();
                shuffleState = context.getShuffle_state();
                currentVolume = context.getDevice().getVolume_percent();
                musicName = resizeText(context.getItem().getName(), 100);
                // artist is down

                for (int i = 0; i < trackList.length; i++) {
                    if (trackList[i].equalsIgnoreCase(context.getRepeat_state())) {
                        trackIndex = i;
                        break;
                    }
                }
                //trackIndex = trackList.;
                lastUpdateTime = System.currentTimeMillis() - 700; // Sync the timer with Spotify's state and add a lil more because of the request time

                // cache track image url so doesn't need to ask spotify api and avoid 304 Not Modified responses
                if (trackCache.get(context.getItem().getId()) != null) {
                    JSONObject track = trackCache.get(context.getItem().getId());
                    //url
                    loadMusicImage(track.getString("url"));
                    artistName = track.getString("artists");
                } else {
                    String trackId = context.getItem().getId();
                    AlbumSimplified track = spotifyApi.getTrack(trackId).build().execute().getAlbum();

                    artistName = resizeText(formatArtists(track.getArtists()), 80);

                    String url = track.getImages() == null || track.getImages().length == 0 ? null : track.getImages()[0].getUrl();
                    loadMusicImage(url);

                    // save url and artist into trackCache
                    JSONObject trackJSON = new JSONObject();

                    trackJSON.put("url", url);
                    trackJSON.put("artists", artistName);
                    trackJSON.put("uri", track.getUri());

                    trackCache.put(trackId, trackJSON);
                }

                if (repeatButton != null) {
                    repeatButton.setTooltip(trackIndex == 0 ? "gui.spoticraft.enable_repeat" : trackIndex == 1 ? "gui.spoticraft.enable_repeat_one" : "gui.spoticraft.disable_repeat");
                    repeatButton.setTexture(trackIndex == 0 ? REPEAT : trackIndex == 1 ? REPEAT_ENABLE : REPEAT_ONE);
                }

                likedSong = isSongLiked(new String[]{currentTrackId});

                if (previousButton != null) {
                    previousButton.setActive(!shuffleState);
                }

                if (context.getItem() instanceof Track trackForHud) {
                    String hudArtist = trackCache.containsKey(trackForHud.getId())
                            ? trackCache.get(trackForHud.getId()).optString("artists", "")
                            : resizeText(formatArtists(trackForHud.getArtists()), 80);
                    SpotifyHudState.update(musicName, hudArtist, musicImage, totalDurationMs, currentProgressMs, musicPlaying);
                }

                updateLikeButton();
                updatePlaylistManagementButtons();
            } else {
                currentTrackUri = null;
                SpotifyHudState.clear();
                ShowTempMessage("gui.spoticraft.no_device");
            }
        } catch (Exception e) {
            // most of the time when the sync failed it's because of an expired token
            if (checkIfExpired()) {return;}
            ShowTempMessage("gui.spoticraft.sync_error");
        }
    }

    // ui stuff
    private void drawMusicControlBar(DrawContext graphics) {
        int barX = this.width / 2 - barWidth / 2;
        int barY = this.height - 15;

        // Draw the background of the bar
        graphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFFCCCCCC);

        // Draw the filled portion of the bar
        int filledWidth = totalDurationMs <= 0 ? 0 : (int) ((currentProgressMs / (float) totalDurationMs) * barWidth);
        graphics.fill(barX, barY, barX + filledWidth, barY + barHeight, 0xFFFFFFFF);

        // Draw the time
        String currentTime = formatTime(currentProgressMs / 1000);
        String durationTime = formatTime(totalDurationMs / 1000);
        drawCenteredString(graphics, currentTime, this.width / 2 - ((barWidth + 30) / 2), barY - 2, 0xFFFFFF);
        drawCenteredString(graphics, durationTime, this.width / 2 + ((barWidth + 30) / 2), barY - 2, 0xFFFFFF);
    }

    private void drawVolumeBar(DrawContext graphics) {
        int barX = this.width - volumeBarWidth - 35;
        int barY = this.height - 15;

        // Draw the background of the volume bar
        graphics.fill(barX, barY, barX + volumeBarWidth, barY + volumeBarHeight, 0xFFCCCCCC);

        // Draw the filled portion of the volume bar
        int filledWidth = (int) ((currentVolume / 100.0) * volumeBarWidth);
        graphics.fill(barX, barY, barX + filledWidth, barY + volumeBarHeight, 0xFFFFFFFF);

        // Draw the volume percentage
        String volumeText = currentVolume + "%";
        drawCenteredString(graphics, volumeText, barX + volumeBarWidth + 15, barY + (volumeBarHeight / 2) - 4, 0xFFFFFF);
    }

    public void drawCenteredString(DrawContext guiGraphics, String text, int centerX, int y, int color) {
        if (MinecraftClient.getInstance().textRenderer == null) {return;}
        textRenderer = MinecraftClient.getInstance().textRenderer;
        // Calculate the text width and draw it centered
        int textWidth = textRenderer.getWidth(text);
        guiGraphics.drawText(textRenderer, text, centerX - textWidth / 2, y, opaqueColor(color), false);
    }

    public void ShowTempMessage(String message) {
        // Set text for the message
        textManager.setText(message, this.width / 2, this.height / 2, 16777215);

        // Clear text after 5 seconds
        if (tempMessageTimer != null) {
            tempMessageTimer.cancel(); // Cancel any existing timer
        }
        tempMessageTimer = new Timer("SpotiCraft-Toast", true);
        tempMessageTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                MinecraftClient.getInstance().execute(() -> textManager.clearText());
            }
        }, 5000);
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        if (keyInput.key() == GLFW.GLFW_KEY_ENTER && searchInput != null && searchInput.isFocused()) {
            search(searchInput.getText());
            return true; // Consume the event
        }

        return super.keyPressed(keyInput);
    }

    private void search(String query) {
        if (query.isEmpty()) {
            return;
        }

        if (checkIfExpired()) {return;}
        CompletableFuture<Paging<Track>> pagingFutureTrack = spotifyApi.searchTracks(query).build().executeAsync();
        CompletableFuture<Paging<AlbumSimplified>> pagingFutureAlbum = spotifyApi.searchAlbums(query).build().executeAsync();
        CompletableFuture<Paging<PlaylistSimplified>> pagingFuturePlaylist = spotifyApi.searchPlaylists(query).build().executeAsync();
        CompletableFuture<Paging<Artist>> pagingFutureArtists = spotifyApi.searchArtists(query).build().executeAsync();

        final Paging<Track> tracks = joinFuture(pagingFutureTrack, "search tracks");
        final Paging<AlbumSimplified> albums = joinFuture(pagingFutureAlbum, "search albums");
        final Paging<PlaylistSimplified> playlists = joinFuture(pagingFuturePlaylist, "search playlists");
        final Paging<Artist> artists = joinFuture(pagingFutureArtists, "search artists");
        if (tracks == null || albums == null || playlists == null || artists == null) {
            ShowTempMessage("gui.spoticraft.sync_error");
            return;
        }

        saveLastAction();

        mainItems.clear();

        for (int i = 0; i < Math.min(2, artists.getItems().length); i++) {
            Artist artist = artists.getItems()[i];
            if (artist == null) {
                continue;
            }
            Identifier artistImage = getImage(artist.getImages() == null || artist.getImages().length == 0 ? null : artist.getImages()[0].getUrl());

            mainItems.add(new Item(
                    artistImage,
                    resizeText(artist.getName(), 200),
                    "Artist",
                    "",
                    artist.getId(),
                    Item.itemType.ARTIST,
                    "",
                    textRenderer));
        }

        for (int i = 0; i < Math.min(5, tracks.getItems().length); i++) {
            Track track = tracks.getItems()[i];
            if (track == null) {
                continue;
            }
            Identifier trackImage = getImage(track.getAlbum().getImages() == null || track.getAlbum().getImages().length == 0 ? null : track.getAlbum().getImages()[0].getUrl());

            mainItems.add(new Item(
                    trackImage,
                    resizeText(track.getName(), 200),
                    resizeText(formatArtists(track.getArtists()), 90),
                    track.getUri(),
                    track.getId(),
                    Item.itemType.TRACK,
                    "",
                    textRenderer));
        }

        for (int i = 0; i < Math.min(5, albums.getItems().length); i++) {
            AlbumSimplified album = albums.getItems()[i];
            if (album == null) {
                continue;
            }
            Identifier albumImage = getImage(album.getImages() == null || album.getImages().length == 0 ? null : album.getImages()[0].getUrl());

            mainItems.add(new Item(
                    albumImage,
                    resizeText(album.getName(), 200),
                    "Album",
                    album.getUri(),
                    album.getId(),
                    Item.itemType.ALBUM,
                    "",
                    textRenderer));
        }

        for (int i = 0; i < Math.min(5, playlists.getItems().length); i++) {
            PlaylistSimplified playlist = playlists.getItems()[i];
            if (playlist == null) {
                continue;
            }
            Identifier playlistImage = getImage(playlist.getImages() == null || playlist.getImages().length == 0 ? null : playlist.getImages()[0].getUrl());

            mainItems.add(new Item(
                    playlistImage,
                    resizeText(playlist.getName(), 200),
                    "Playlist",
                    playlist.getUri(),
                    playlist.getId(),
                    Item.itemType.PLAYLIST,
                    "",
                    textRenderer));
        }

        addEmpty(mainItems);

        mainPanel.setInfo(mainItems);
    }

    // mouse action
    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        double mouseX = click.x();
        double mouseY = click.y();
        int barX = this.width / 2 - barWidth / 2;
        int barY = this.height - 15;
        int volumeBarX = this.width - volumeBarWidth - 35;
        int volumeBarY = this.height - 15;

        if (mouseX >= barX && mouseX <= barX + barWidth && mouseY >= barY && mouseY <= barY + barHeight) {
            currentProgressMs = (int) (((mouseX - barX) / barWidth) * totalDurationMs);
            return changePositionInCurrentTrack();
        }

        if (mouseX >= volumeBarX && mouseX <= volumeBarX + volumeBarWidth && mouseY >= volumeBarY && mouseY <= volumeBarY + volumeBarHeight) {
            updateVolume((int) ((mouseX - volumeBarX) / volumeBarWidth * 100));
            return true;
        }

        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseReleased(Click click) {
        double mouseX = click.x();
        double mouseY = click.y();
        int barX = this.width / 2 - barWidth / 2;
        int barY = this.height - 15;
        int volumeBarX = this.width - volumeBarWidth - 35;
        int volumeBarY = this.height - 15;

        // Check if dragging is within the bounds of the progress bar
        if (mouseX >= barX && mouseX <= barX + barWidth && mouseY >= barY && mouseY <= barY + barHeight) {
            return changePositionInCurrentTrack();
        }

        if (mouseX >= volumeBarX && mouseX <= volumeBarX + volumeBarWidth && mouseY >= volumeBarY && mouseY <= volumeBarY + volumeBarHeight) {
            updateVolume((int) ((mouseX - volumeBarX) / volumeBarWidth * 100));
            return true;
        }

        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        double mouseX = click.x();
        double mouseY = click.y();
        int barX = this.width / 2 - barWidth / 2;
        int barY = this.height - 15;
        int volumeBarX = this.width - volumeBarWidth - 35;
        int volumeBarY = this.height - 15;

        // Check if dragging is within the bounds of the progress bar
        if (mouseX >= barX && mouseX <= barX + barWidth && mouseY >= barY && mouseY <= barY + barHeight) {
            // Update the music progress as the user drags
            currentProgressMs = (int) (((mouseX - barX) / barWidth) * totalDurationMs);
            currentProgressMs = Math.max(0, Math.min(currentProgressMs, totalDurationMs)); // Clamp between 0 and total duration
            return true;
        }

        if (mouseX >= volumeBarX && mouseX <= volumeBarX + volumeBarWidth && mouseY >= volumeBarY && mouseY <= volumeBarY + volumeBarHeight) {
            //updateVolume((int) ((mouseX - volumeBarX) / volumeBarWidth * 100));
            currentVolume = (int) ((mouseX - volumeBarX) / volumeBarWidth * 100);
            return true;
        }

        return super.mouseDragged(click, deltaX, deltaY);
    }

    // ui controls
    private void toggleMusicPlayback() {
        if (checkIfExpired()) {return;}

        try {
            if (musicPlaying) {
                spotifyApi.pauseUsersPlayback().build().execute();
                musicPlaying = false;
            } else {
                spotifyApi.startResumeUsersPlayback().build().execute();
                syncData();
                musicPlaying = true;
            }
        } catch (Exception e) {
            ShowTempMessage(e.getMessage());
        }
    }

    private void updateVolume(int newVolume) {
        currentVolume = Math.max(0, Math.min(newVolume, 100)); // Clamp between 0 and 100

        // Send the volume update to Spotify API
        try {
            if (checkIfExpired()) {return;}
            spotifyApi.setVolumeForUsersPlayback(currentVolume).build().executeAsync();
        } catch (Exception e) {
            ShowTempMessage("Failed to set volume: " + e.getMessage());
        }
    }

    public void showPlaylist(String playlistId, String playlistContext) throws IOException, ParseException, SpotifyWebApiException, URISyntaxException {
        if (checkIfExpired()) {return;}

        List<JSONObject> tracks = loadAllPlaylistTracksJson(playlistId);

        saveLastAction();

        mainItems.clear();

        mainItems.add(new Item(
                Identifier.of(MOD_ID, "textures/gui/play.png"),
                Text.translatable("gui.spoticraft.play_playlist").getString(),
                "",
                "",
                Item.itemType.PLAY_ALBUM_PLAYLIST,
                playlistContext,
                textRenderer
        ));

        for (JSONObject trackJson : tracks) {
            if (trackJson == null) {
                continue;
            }

            addTrackItem(trackJson, playlistContext);
        }

        if (tracks.isEmpty()) {
            mainItems.add(new Item(
                    EMPTY_IMAGE,
                    "No tracks available",
                    "Spotify did not return any playable tracks for this playlist.",
                    "",
                    "",
                    Item.itemType.CATEGORY,
                    "",
                    textRenderer
            ));
        }

        addEmpty(mainItems);

        mainPanel.setInfo(mainItems);
    }

    private List<JSONObject> loadAllPlaylistTracksJson(String playlistId) throws IOException, URISyntaxException {
        List<JSONObject> allTracks = new ArrayList<>();
        String accessToken = TokenStorage.token.getString("access_token");
        String nextUrl = "https://api.spotify.com/v1/playlists/" + playlistId +
                "?fields=tracks.items(track(id,name,uri,album(images),artists(name))),tracks.next,tracks.total";

        while (nextUrl != null && !nextUrl.isBlank()) {
            JSONObject page = SpotifyAuthHandler.getSpotifyJson(nextUrl, accessToken);
            JSONObject tracksObject = page.has("tracks") ? page.optJSONObject("tracks") : page;
            if (tracksObject == null) {
                break;
            }

            JSONArray items = tracksObject.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }

                    JSONObject track = item.optJSONObject("track");
                    if (track != null) {
                        allTracks.add(track);
                    }
                }
            }

            nextUrl = tracksObject.optString("next", null);
        }

        return allTracks;
    }

    public void showAlbum(String albumId, String albumContext) throws IOException, ParseException, SpotifyWebApiException {
        if (checkIfExpired()) {return;}

        Paging<TrackSimplified> tracks = spotifyApi.getAlbumsTracks(albumId).build().execute();
        Album album = spotifyApi.getAlbum(albumId).build().execute();
        Identifier albumImage = getImage(album.getImages() == null || album.getImages().length == 0 ? null : album.getImages()[0].getUrl());

        saveLastAction();

        mainItems.clear();

        mainItems.add(new Item(
                Identifier.of(MOD_ID, "textures/gui/play.png"),
                Text.translatable("gui.spoticraft.play_album").getString(),
                "",
                "",
                Item.itemType.PLAY_ALBUM_PLAYLIST,
                albumContext,
                textRenderer
        ));

        for (TrackSimplified track : tracks.getItems()) {
            if (track == null) {
                continue;
            }

            mainItems.add(new Item(
                    albumImage,
                    resizeText(track.getName(), 200),
                    resizeText(formatArtists(track.getArtists()), 90),
                    track.getUri(),
                    track.getId(),
                    Item.itemType.TRACK,
                    albumContext,
                    textRenderer));
        }

        addEmpty(mainItems);

        mainPanel.setInfo(mainItems);
    }

    private void showTrack(String trackId, String trackUri, String trackName, String context) throws IOException, ParseException, SpotifyWebApiException {
        if (checkIfExpired()) {return;}

        String url;
        if (trackCache.get(trackId) != null) {
            JSONObject trackJson = trackCache.get(trackId);
            //url
            url = trackJson.getString("url");
        } else {
            AlbumSimplified trackAlbum = spotifyApi.getTrack(trackId).build().execute().getAlbum();

            url = trackAlbum.getImages() == null || trackAlbum.getImages().length == 0 ? null : trackAlbum.getImages()[0].getUrl();

            // save url and artist into trackCache
            JSONObject trackJSON = new JSONObject();

            trackJSON.put("url", url);
            trackJSON.put("artists", resizeText(formatArtists(trackAlbum.getArtists()), 80));
            trackJSON.put("uri", trackUri);

            trackCache.put(trackId, trackJSON);
        }

        Identifier trackImage = getImage(url);

        mainItems.add(new Item(
                trackImage,
                resizeText(trackName, 200),
                resizeText(trackCache.get(trackId).optString("artists", "Track"), 90),
                trackUri,
                trackId,
                Item.itemType.TRACK,
                context,
                textRenderer));
    }

    private void addTrackItem(Track track, String context) {
        if (track == null) {
            return;
        }

        String url = track.getAlbum() == null || track.getAlbum().getImages() == null || track.getAlbum().getImages().length == 0
                ? null
                : track.getAlbum().getImages()[0].getUrl();

        JSONObject trackJSON = new JSONObject();
        trackJSON.put("url", url);
        trackJSON.put("artists", resizeText(formatArtists(track.getArtists()), 80));
        trackJSON.put("uri", track.getUri());
        trackCache.put(track.getId(), trackJSON);

        mainItems.add(new Item(
                getImage(url),
                resizeText(track.getName(), 200),
                resizeText(formatArtists(track.getArtists()), 90),
                track.getUri(),
                track.getId(),
                Item.itemType.TRACK,
                context,
                textRenderer));
    }

    private void addTrackItem(JSONObject track, String context) {
        if (track == null) {
            return;
        }

        String trackId = track.optString("id", "");
        String trackUri = track.optString("uri", "");
        String trackName = track.optString("name", "Unknown Track");
        JSONObject album = track.optJSONObject("album");
        JSONArray images = album == null ? null : album.optJSONArray("images");
        String url = null;
        if (images != null && images.length() > 0) {
            JSONObject firstImage = images.optJSONObject(0);
            if (firstImage != null) {
                url = firstImage.optString("url", null);
            }
        }

        JSONArray artistsArray = track.optJSONArray("artists");
        StringBuilder artistsBuilder = new StringBuilder();
        if (artistsArray != null) {
            for (int i = 0; i < artistsArray.length(); i++) {
                JSONObject artist = artistsArray.optJSONObject(i);
                if (artist == null) {
                    continue;
                }

                if (!artistsBuilder.isEmpty()) {
                    artistsBuilder.append(", ");
                }
                artistsBuilder.append(artist.optString("name", ""));
            }
        }

        String artistsText = artistsBuilder.isEmpty() ? "Track" : artistsBuilder.toString();

        JSONObject trackJSON = new JSONObject();
        trackJSON.put("url", url);
        trackJSON.put("artists", resizeText(artistsText, 90));
        trackJSON.put("uri", trackUri);
        trackCache.put(trackId, trackJSON);

        mainItems.add(new Item(
                getImage(url),
                resizeText(trackName, 200),
                resizeText(artistsText, 90),
                trackUri,
                trackId,
                Item.itemType.TRACK,
                context,
                textRenderer));
    }

    public void showLikedTracks() throws IOException, ParseException, SpotifyWebApiException {
        if (checkIfExpired()) {return;}

        Paging<SavedTrack> tracks = spotifyApi.getUsersSavedTracks().build().execute();

        saveLastAction();

        mainItems.clear();

        for (SavedTrack savedTrack : tracks.getItems()) {
            if (savedTrack == null || savedTrack.getTrack() == null) {
                continue;
            }

            Track track = savedTrack.getTrack();
            addTrackItem(track, "");
        }

        addEmpty(mainItems);

        mainPanel.setInfo(mainItems);
    }

    public void showArtist(String artistId) throws IOException, ParseException, SpotifyWebApiException {
        if (checkIfExpired()) {return;}

        Track[] tracks = spotifyApi.getArtistsTopTracks(artistId, userCountryCode).build().execute();
        Paging<AlbumSimplified> albums = spotifyApi.getArtistsAlbums(artistId).build().execute();

        saveLastAction();

        mainItems.clear();

        for (Track track : tracks) {
            if (track == null) {
                continue;
            }

            addTrackItem(track, "");
        }

        for (int i = 0; i < Math.min(5, albums.getItems().length); i++) {
            AlbumSimplified album = albums.getItems()[i];
            if (album == null) {
                continue;
            }

            Identifier albumImage = getImage(album.getImages() == null || album.getImages().length == 0 ? null : album.getImages()[0].getUrl());

            mainItems.add(new Item(
                    albumImage,
                    resizeText(album.getName(), 200),
                    "Album",
                    album.getUri(),
                    album.getId(),
                    Item.itemType.ALBUM,
                    "",
                    textRenderer
            ));
        }

        addEmpty(mainItems);

        mainPanel.setInfo(mainItems);
    }

    private void showHomePage() throws IOException, ParseException, SpotifyWebApiException {
        Paging<AlbumSimplified> newRelease = spotifyApi.getListOfNewReleases().build().execute();

        // if main items is empty it means that the ui just init
        if (!mainItems.isEmpty()) {
            saveLastAction();
        }

        mainItems.clear();

        mainItems.add(new Item(
                EMPTY_IMAGE,
                Text.translatable("gui.spoticraft.new_releases").getString(),
                "",
                "",
                Item.itemType.CATEGORY,
                "",
                textRenderer
        ));

        for (int i = 0; i < Math.min(5, newRelease.getItems().length); i++) {
            AlbumSimplified album = newRelease.getItems()[i];
            if (album == null) {
                continue;
            }

            Identifier albumImage = getImage(album.getImages() == null || album.getImages().length == 0 ? null : album.getImages()[0].getUrl());

            mainItems.add(new Item(
                    albumImage,
                    album.getName(),
                    "Album",
                    album.getUri(),
                    album.getId(),
                    Item.itemType.ALBUM,
                    "",
                    textRenderer
            ));
        }

        addEmpty(mainItems);

        mainPanel.setInfo(mainItems);
    }

    private void showUserPlaylists() throws IOException, ParseException, SpotifyWebApiException {
        Paging<PlaylistSimplified> playlistSimplifiedPaging = spotifyApi.getListOfCurrentUsersPlaylists().build().execute();
        Paging<SavedAlbum> savedAlbumPaging = spotifyApi.getCurrentUsersSavedAlbums().build().execute();

        playlistItems.clear();
        editablePlaylists.clear();

        playlistItems.add(new Item(
                Identifier.of(MOD_ID, "textures/gui/liked_songs.png"),
                Text.translatable("gui.spoticraft.liked_songs").getString(),
                "",
                "",
                Item.itemType.LIKED_TRACK,
                "",
                textRenderer));

        for (SavedAlbum savedAlbum : savedAlbumPaging.getItems()) {
            if (savedAlbum == null || savedAlbum.getAlbum() == null) {
                continue;
            }

            Album album = savedAlbum.getAlbum();
            Identifier albumImage = getImage(album.getImages() == null || album.getImages().length == 0 ? null : album.getImages()[0].getUrl());

            playlistItems.add(new Item(
                    albumImage,
                    resizeText(album.getName(), 100),
                    "Album",
                    album.getUri(),
                    album.getId(),
                    Item.itemType.ALBUM,
                    "",
                    textRenderer));
        }

        for (PlaylistSimplified playlist : playlistSimplifiedPaging.getItems()) {
            if (playlist == null) {
                continue;
            }

            Identifier playlistImage = getImage(playlist.getImages() == null || playlist.getImages().length == 0 ? null : playlist.getImages()[0].getUrl());
            boolean ownedByCurrentUser = playlist.getOwner() != null && currentUserId != null && !currentUserId.isBlank()
                    && currentUserId.equals(playlist.getOwner().getId());
            boolean collaborative = playlist.getIsCollaborative();
            if (ownedByCurrentUser || collaborative) {
                editablePlaylists.add(new PlaylistSelection(playlist.getId(), playlist.getName()));
            }

            playlistItems.add(new Item(
                    playlistImage,
                    resizeText(playlist.getName(), 100),
                    "Playlist",
                    playlist.getUri(),
                    playlist.getId(),
                    Item.itemType.PLAYLIST,
                    "",
                    textRenderer));
        }

        addEmpty(playlistItems);

        if (playlistPanel != null) {
            playlistPanel.setInfo(playlistItems);
        }

        if (selectedPlaylistIndex >= editablePlaylists.size()) {
            selectedPlaylistIndex = 0;
        }

        updatePlaylistManagementButtons();
    }

    private void populateHomeFallback() {
        mainItems.clear();

        mainItems.add(new Item(
                EMPTY_IMAGE,
                Text.translatable("gui.spoticraft.home").getString(),
                "",
                "",
                Item.itemType.CATEGORY,
                "",
                textRenderer
        ));

        if (currentTrackId != null && currentTrackUri != null && musicName != null) {
            mainItems.add(new Item(
                    musicImage != null ? musicImage : EMPTY_IMAGE,
                    musicName,
                    artistName,
                    currentTrackUri,
                    currentTrackId,
                    Item.itemType.TRACK,
                    "",
                    textRenderer
            ));
        }

        addEmpty(mainItems);

        if (mainPanel != null) {
            mainPanel.setInfo(mainItems);
        }
    }

    private void populatePlaylistFallback() {
        playlistItems.clear();
        editablePlaylists.clear();

        playlistItems.add(new Item(
                Identifier.of(MOD_ID, "textures/gui/liked_songs.png"),
                Text.translatable("gui.spoticraft.liked_songs").getString(),
                "",
                "",
                Item.itemType.LIKED_TRACK,
                "",
                textRenderer));

        addEmpty(playlistItems);

        if (playlistPanel != null) {
            playlistPanel.setInfo(playlistItems);
        }

        updatePlaylistManagementButtons();
    }

    public void goBack() {
        if (itemCache.isEmpty() || itemCache.size() == 1)
            return;

        itemCacheForward.addLast(new ArrayList<>(mainItems));
        mainItems.clear();
        mainItems = new ArrayList<>(itemCache.getLast());
        mainPanel.setInfo(mainItems);
        itemCache.removeLast();
    }

    public void goForward() {
        if (itemCacheForward.isEmpty())
            return;

        itemCache.addLast(new ArrayList<>(mainItems));
        mainItems.clear();
        mainItems = new ArrayList<>(itemCacheForward.getLast());
        mainPanel.setInfo(mainItems);
        itemCacheForward.removeLast();
    }

    private void saveLastAction() {
        itemCache.addLast(new ArrayList<>(mainItems));
    }

    // other
    private String formatTime(int seconds) {
        int minutes = seconds / 60;
        int remainingSeconds = seconds % 60;
        return String.format("%d:%02d", minutes, remainingSeconds);
    }

    public void loadMusicImage(String url) {
        musicImage = ImageHandler.downloadImage(url); // Download and set the image
    }

    private boolean changePositionInCurrentTrack() {
        if (totalDurationMs <= 0) {
            return false;
        }

        try {
            if (checkIfExpired()) {return false;}

            spotifyApi.seekToPositionInCurrentlyPlayingTrack(currentProgressMs).build().executeAsync();
        } catch (Exception e) {
            LOGGER.warn("Failed to seek in current track", e);
        }
        return true;
    }

    private String resizeText(String text, int maxSize) {

        int size = textRenderer.getWidth(text);

        if (size <= maxSize) {
            return text;
        }

        for (int i = text.length() - 1; i > 0; i--) {
            String res = text.substring(0, i);
            if (textRenderer.getWidth(res) <= maxSize) {
                if (i - 3 < 0) {
                    return text;
                }

                return text.substring(0, i - 3) + "...";
            }
        }

        return text;
    }

    private void addEmpty(List<Item> itemsList) {
        itemsList.add(new Item(
                EMPTY_IMAGE,
                "",
                "",
                "",
                Item.itemType.EMPTY,
                "",
                textRenderer
        ));
    }

    private String formatArtists(ArtistSimplified[] artists) {
        // since some song can have multiple artist we do this to add then
        StringBuilder artistsFormated = new StringBuilder();
        for (ArtistSimplified artist : artists) {
            artistsFormated.append(artist.getName()).append(", ");
        }
        // cut the ", " on the last artist
        return artistsFormated.substring(0, artistsFormated.length() - 2);
    }

    private Identifier getImage(String url) {
        Identifier image;
        if (url == null) {
            image = Identifier.of(MOD_ID, "textures/gui/default_playlist_image.png");
        } else {
            image = ImageHandler.downloadImage(url);
        }

        return image;
    }

    private void addOrRemoveLikedSong() throws IOException, ParseException, SpotifyWebApiException {
        String[] ids = new String[]{currentTrackId};

        if (likedSong) {
            spotifyApi.removeUsersSavedTracks(ids).build().executeAsync();
            likedSong = false;
        } else {
            spotifyApi.saveTracksForUser(ids).build().executeAsync();
            likedSong = true;
        }

        updateLikeButton();
    }

    private boolean isSongLiked(String[] ids) throws IOException, ParseException, SpotifyWebApiException {
        Boolean[] liked = spotifyApi.checkUsersSavedTracks(ids).build().execute();

        return liked != null && liked[0];
    }

    private void updateLikeButton() {
        if (likeButton != null) {
            likeButton.setTexture(likedSong ? LIKED_TEXTURE : LIKE_TEXTURE);

            likeButton.setTooltip(likedSong ? "gui.spoticraft.liked" : "gui.spoticraft.like");

            likeButton.setX(imageWidth + 10 + textRenderer.getWidth(musicName) + 2);
        }
    }

    @Override
    public void removed() {
        if (tempMessageTimer != null) {
            tempMessageTimer.cancel();
            tempMessageTimer = null;
        }

        if (progressUpdateTimer != null) {
            progressUpdateTimer.cancel();
            progressUpdateTimer = null;
        }

        if (instance == this) {
            instance = null;
        }
    }

    private boolean checkIfExpired() {
        try {
            TokenStorage.checkIfExpired();
            tokenExpired = false;
            return false;
        } catch (IOException | URISyntaxException e) {
            tokenExpired = true;
            return true;
        }
    }
}
