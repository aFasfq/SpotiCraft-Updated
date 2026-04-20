package com.leonimust.client.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ScrollableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

import static com.leonimust.SpotiCraft.LOGGER;

public class ItemScrollPanel extends ScrollableWidget {

    private List<Item> items = new ArrayList<>();
    private final int itemHeight = 48;

    // Scrollbar dragging state
    private boolean isScrolling;
    private double startMouseY;
    private double startScrollY;

    public ItemScrollPanel(int width, int height, int x, int y) {
        super(x, y, width, height, Text.of(""));
    }

    public void setInfo(List<Item> content) {
        this.items = content;
        this.setScrollY(0);
    }

    @Override
    protected int getContentsHeightWithPadding() {
        return Math.max(items.size(), 1) * itemHeight;
    }

    @Override
    protected double getDeltaYPerScroll() {
        return itemHeight * 3;
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        int panelLeft = this.getX();
        int panelTop = this.getY();
        int panelRight = panelLeft + this.width;
        int panelBottom = panelTop + this.height;

        context.fill(panelLeft, panelTop, panelRight, panelBottom, 0x22181818);
        context.fill(panelLeft, panelTop, panelRight, panelTop + 1, 0x44FFFFFF);
        context.fill(panelLeft, panelBottom - 1, panelRight, panelBottom, 0x44FFFFFF);
        context.fill(panelLeft, panelTop, panelLeft + 1, panelBottom, 0x44FFFFFF);
        context.fill(panelRight - 1, panelTop, panelRight, panelBottom, 0x44FFFFFF);

        if (items == null || items.isEmpty()) {
            return;
        }

        int relativeY = (int) (panelTop - this.getScrollY());
        for (Item item : items) {
            if (item != null && relativeY + itemHeight >= panelTop && relativeY <= panelBottom) {
                item.draw(panelLeft, relativeY, context);
            }
            relativeY += itemHeight;
        }

        if (overflows()) {
            drawScrollbar(context, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (click.button() == 0 && overflows()) {
            if (isMouseOverScrollbar(click.x(), click.y())) {
                isScrolling = true;
                startMouseY = click.y();
                startScrollY = getScrollY();
                return true;
            }
        }

        // Existing item click handling
        int relativeY = (int) (this.getY() - this.getScrollY());
        for (Item item : items) {
            if (item != null && item.isMouseOver((int) click.x(), (int) click.y(), this.getX(), relativeY)) {
                try {
                    item.onClick();
                } catch (Exception e) {
                    LOGGER.error("Failed to handle Spotify item click", e);
                    SpotifyScreen screen = SpotifyScreen.getInstance();
                    if (screen != null) {
                        screen.ShowTempMessage(e.getMessage() == null ? "gui.spoticraft.sync_error" : e.getMessage());
                    }
                    return true;
                }
                return true;
            }
            relativeY += itemHeight;
        }

        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (isScrolling) {
            double maxScrollY = getMaxScrollY();
            double scrollDelta = (click.y() - startMouseY) * (maxScrollY / (this.height - this.getScrollbarThumbHeight()));
            setScrollY(MathHelper.clamp(startScrollY + scrollDelta, 0, maxScrollY));
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (click.button() == 0) {
            isScrolling = false;
        }
        return super.mouseReleased(click);
    }

    private boolean isMouseOverScrollbar(double mouseX, double mouseY) {
        int scrollbarX = this.getX() + this.width - 6; // Match scrollbarWidth
        return mouseX >= scrollbarX &&
                mouseX <= scrollbarX + 6 && // Match scrollbarWidth
                mouseY >= this.getScrollbarThumbY() &&
                mouseY <= this.getScrollbarThumbY() + this.getScrollbarThumbHeight();
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        // Narration implementation
    }
}
