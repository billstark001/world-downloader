package io.github.billstark001.worldmirror.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Minecraft 1.21.11 rendering and screen-installation adapter. */
abstract class StatusScreenApi extends Screen {
    protected interface Canvas {
        void text(Component text, int x, int y, int color);
        void centered(Component text, int x, int y, int color);
    }

    protected StatusScreenApi(Component title) {
        super(title);
    }

    protected abstract void renderContent(Canvas canvas);

    @Override
    public final void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);
        super.render(graphics, mouseX, mouseY, delta);
        renderContent(new Canvas() {
            @Override
            public void text(Component text, int x, int y, int color) {
                graphics.drawString(font, text, x, y, color);
            }

            @Override
            public void centered(Component text, int x, int y, int color) {
                graphics.drawCenteredString(font, text, x, y, color);
            }
        });
    }

    protected static void showScreen(Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }
}
