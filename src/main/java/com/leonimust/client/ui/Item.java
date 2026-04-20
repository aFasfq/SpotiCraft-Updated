package com.leonimust.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.apache.hc.core5.http.ParseException;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Objects;

public class Item {

    private final Identifier image;
    private final String name;
    private final String subtitle;
    private final TextRenderer font;
    private final String itemId;
    private final String itemUri;
    private final itemType type;
    private final String contextUri;

    public enum itemType {
        PLAYLIST,
        ALBUM,
        PLAY_ALBUM_PLAYLIST,
        TRACK,
        LIKED_TRACK,
        ARTIST,
        CATEGORY,
        EMPTY
    }

    public Item(Identifier image, String name, String uri, String id, itemType type, String contextId, TextRenderer font) {
        this(image, name, "", uri, id, type, contextId, font);
    }

    public Item(Identifier image, String name, String subtitle, String uri, String id, itemType type, String contextId, TextRenderer font) {
        this.image = image;
        this.name = name == null || name.isBlank() ? fallbackTitle(type) : name;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.font = font;
        this.itemUri = uri;
        this.itemId = id;
        this.type = type;
        this.contextUri = contextId;
    }

    public void draw(int x, int y, DrawContext graphics) {
        int imageHeight = 40;
        int imageWidth = 40;

        graphics.drawTexture(RenderPipelines.GUI_TEXTURED, image, x, y, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);
        int textX = x + imageWidth + 8;
        boolean singleLine = type == itemType.CATEGORY || type == itemType.PLAY_ALBUM_PLAYLIST || type == itemType.LIKED_TRACK || subtitle.isBlank();
        graphics.drawText(font, name, textX, singleLine ? y + 15 : y + 8, 0xFFFFFFFF, true);

        if (type == itemType.EMPTY || singleLine) {
            return;
        }

        graphics.drawText(font, subtitle, textX, y + 24, 0xFFAAAAAA, true);
    }

    public boolean isMouseOver(int mouseX, int mouseY, int x, int y) {
        int imageHeight = 40;
        int imageWidth = 40;
        return mouseX >= x && mouseX <= x + imageWidth + 5 + font.getWidth(name)
                && mouseY >= y && mouseY <= y + imageHeight;
    }

    public void onClick() throws IOException, ParseException, SpotifyWebApiException, URISyntaxException {
        if (type == itemType.EMPTY) {
            return;
        }

        if (type == itemType.TRACK) {
            try {
                if (!Objects.equals(contextUri, "") && contextUri != null) {
                    SpotifyScreen.spotifyApi.startResumeUsersPlayback().context_uri(contextUri).offset(JsonParser.parseString("{\"uri\":\"" + this.itemUri + "\"}").getAsJsonObject()).build().execute();
                } else {
                    SpotifyScreen.spotifyApi.startResumeUsersPlayback().uris((JsonArray) JsonParser.parseString("[\"" + this.itemUri + "\"]")).build().execute();
                }
                SpotifyScreen.getInstance().scheduleSyncData(250);
            } catch (IOException | SpotifyWebApiException | ParseException e) {
                SpotifyScreen.getInstance().ShowTempMessage(e.getMessage());
            }
        }

        if (type == itemType.ALBUM) {
            SpotifyScreen.getInstance().showAlbum(this.itemId, this.itemUri);
        }

        if (type == itemType.PLAYLIST) {
            SpotifyScreen.getInstance().showPlaylist(this.itemId, this.itemUri);
        }

        if (type == itemType.PLAY_ALBUM_PLAYLIST) {
            try {
                SpotifyScreen.spotifyApi.startResumeUsersPlayback().context_uri(this.contextUri).build().execute();
                SpotifyScreen.getInstance().scheduleSyncData(250);
            } catch (IOException | SpotifyWebApiException | ParseException ignored) {
            }
        }

        if (type == itemType.LIKED_TRACK) {
            SpotifyScreen.getInstance().showLikedTracks();
        }

        if (type == itemType.ARTIST) {
            SpotifyScreen.getInstance().showArtist(this.itemId);
        }
    }

    public String getName() {
        return name;
    }

    public String getItemId() {
        return itemId;
    }

    public itemType getType() {
        return type;
    }

    private static String fallbackTitle(itemType type) {
        return switch (type) {
            case PLAYLIST -> "Playlist";
            case ALBUM -> "Album";
            case PLAY_ALBUM_PLAYLIST -> "Play";
            case TRACK -> "Unknown Track";
            case LIKED_TRACK -> "Liked Songs";
            case ARTIST -> "Artist";
            case CATEGORY -> "Section";
            case EMPTY -> "";
        };
    }
}
