package com.leonimust.client.ui;

import com.leonimust.client.HudConfig;
import com.leonimust.client.SpotifyHudState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class SpotifyHudEditorScreen extends Screen {
    private boolean dragging;
    private int dragOffsetX;
    private int dragOffsetY;

    public SpotifyHudEditorScreen() {
        super(Text.literal("SpotiCraft HUD Editor"));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xAA080404);
        super.render(context, mouseX, mouseY, delta);

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.textRenderer != null) {
            context.drawText(client.textRenderer, "Drag the Spotify HUD to move it. Press Esc to save.", 16, 16, 0xFFFFFFFF, false);
        }

        HudConfig.Config config = HudConfig.get();
        SpotifyHudState.render(context, client.textRenderer, config.x(), config.y(), true);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        HudConfig.Config config = HudConfig.get();
        if (click.button() == 0 && SpotifyHudState.contains(click.x(), click.y(), config.x(), config.y())) {
            dragging = true;
            dragOffsetX = (int) click.x() - config.x();
            dragOffsetY = (int) click.y() - config.y();
            return true;
        }

        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (!dragging) {
            return super.mouseDragged(click, deltaX, deltaY);
        }

        int maxX = Math.max(0, this.width - SpotifyHudState.getWidth());
        int maxY = Math.max(0, this.height - SpotifyHudState.getHeight());
        int newX = Math.max(0, Math.min(maxX, (int) click.x() - dragOffsetX));
        int newY = Math.max(0, Math.min(maxY, (int) click.y() - dragOffsetY));
        HudConfig.updatePosition(newX, newY);
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = false;
        return super.mouseReleased(click);
    }
}
