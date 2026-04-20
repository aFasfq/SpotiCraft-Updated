# SpotiCraft

SpotiCraft is a Fabric `1.21.10` mod that lets you control Spotify from inside Minecraft.

It supports:
- Spotify login with your own Spotify developer app
- Play, pause, next, previous, shuffle, repeat, volume
- Search for tracks, artists, albums, and playlists
- Artist pages, album pages, playlists, liked songs
- Add and remove the current track from your editable playlists
- An always-on dark HUD strip with cover art, title, artist, progress, and duration
- A movable HUD editor

Spotify Premium is required.

## Credits

This project is based on the discontinued original SpotiCraft project by LeonimusTTV:

- Original project: https://github.com/LeonimusTTV/SpotiCraft

This version updates the mod for modern Fabric/Minecraft, replaces the abandoned auth setup with user-owned Spotify app configuration, and continues development from there.

## Requirements

- Minecraft `1.21.10`
- Fabric Loader
- Fabric API
- Java `21`
- Spotify Premium
- Your own Spotify developer app/client ID

## Install

1. Put the mod jar into your Fabric `mods` folder.
2. Make sure Fabric API is installed.
3. Launch Minecraft once.

## Spotify App Setup

The original embedded Spotify app is not reliable for public use, so this build uses your own Spotify developer app.

1. Go to the Spotify Developer Dashboard and create an app.
2. Enable `Web API`.
3. Add this exact redirect URI:

```text
http://127.0.0.1:12589/callback
```

4. Launch Minecraft and press `P` once.
5. SpotiCraft will create this config file inside your Minecraft instance:

```text
<instance folder>/spoticraft/spotify_app.json
```

6. Put your Spotify `client_id` into that file.

Example:

```json
{
  "client_id": "YOUR_SPOTIFY_CLIENT_ID",
  "redirect_uri": "http://127.0.0.1:12589/callback",
  "notes": "Create your own Spotify app and paste its client_id here. In the Spotify dashboard, add the same redirect_uri exactly."
}
```

7. If you previously authenticated with a different app, delete:

```text
<instance folder>/spoticraft/spotify_tokens.json
```

8. Press `P` again and complete Spotify login in your browser.

## Controls

- `P`: Open SpotiCraft
- `O`: Open the HUD editor

You can also rebind both keys in Minecraft Controls under `SpotiCraft`.

## How To Use

- Open the main UI with `P`
- Use the search bar at the top to search Spotify
- Click playlists, albums, artists, or tracks to browse and play them
- Use the playback buttons at the bottom for transport controls
- Choose a target playlist at the top-left, then use `Add Track` or `Remove Track`
- Press `O` to drag the always-on HUD strip wherever you want

## Notes

- Playback is controlled through Spotify Connect, so Spotify still handles the actual audio output.
- The HUD updates in the background and should not require reopening the main GUI to refresh.
- Some Spotify content can still be limited by account/device/API behavior.

## Build

```bash
./gradlew build
```

Built jars are placed in:

```text
build/libs/
```
