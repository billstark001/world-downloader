package io.github.billstark001.worldmirror.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Minecraft 26.1 rendering and screen-installation adapter. */
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
    public final void extractRenderState(GuiGraphicsExtractor graphics,
                                         int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        renderContent(new Canvas() {
            @Override
            public void text(Component text, int x, int y, int color) {
                graphics.text(font, text, x, y, color);
            }

            @Override
            public void centered(Component text, int x, int y, int color) {
                graphics.centeredText(font, text, x, y, color);
            }
        });
    }

    protected static void showScreen(Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }
}
