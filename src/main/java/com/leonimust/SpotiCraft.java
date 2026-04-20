package com.leonimust;

import com.leonimust.client.HudConfig;
import com.leonimust.client.SpotifyHudState;
import com.leonimust.client.SpotifyPlaybackMonitor;
import com.leonimust.client.TokenStorage;
import com.leonimust.client.ui.SpotifyHudEditorScreen;
import com.leonimust.client.ui.SpotifyScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpotiCraft implements ClientModInitializer {
	public static final String MOD_ID = "spoticraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final KeyBinding.Category KEY_CATEGORY = KeyBinding.Category.create(Identifier.of(MOD_ID, "general"));

	// Remove static modifiers (client-only fields)
	private KeyBinding openSpotifyKey;
	private KeyBinding editHudKey;

	@Override
	public void onInitializeClient() {
		openSpotifyKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.spoticraft.open_spotify",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_P,
				KEY_CATEGORY
		));

		editHudKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.spoticraft.edit_hud",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_O,
				KEY_CATEGORY
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			SpotifyPlaybackMonitor.tick(client);

			while (openSpotifyKey.wasPressed()) {
				TokenStorage.loadToken();
				MinecraftClient.getInstance().setScreen(new SpotifyScreen());
			}

			while (editHudKey.wasPressed()) {
				MinecraftClient.getInstance().setScreen(new SpotifyHudEditorScreen());
			}
		});

		HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
			MinecraftClient client = MinecraftClient.getInstance();
			HudConfig.Config config = HudConfig.get();
			if (!config.visible() || client.currentScreen != null || client.textRenderer == null) {
				return;
			}

			SpotifyHudState.render(drawContext, client.textRenderer, config.x(), config.y(), false);
		});
	}
}
