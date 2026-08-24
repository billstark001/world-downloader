package io.github.billstark001.worldmirror.config;

import me.shedaniel.clothconfig2.gui.entries.DropdownBoxEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.function.Function;

/** Minecraft 26.1.2 draw-call adapter for the shared read-only dropdown top cell. */
final class EnumDropdownGuiApi {
    private EnumDropdownGuiApi() { }

    static <T> DropdownBoxEntry.SelectionTopCellElement<T> createTopCell(
            T value, Function<T, Component> label) {
        return new ReadOnlyDropdownTopCell<>(value, label) {
            @Override
            public void extractRenderState(GuiGraphicsExtractor graphics,
                                           int mouseX, int mouseY,
                                           int x, int y, int width, int height, float delta) {
                RenderModel model = layout(x, y, width, height);
                var font = Minecraft.getInstance().font;
                graphics.text(font, model.text(), model.textX(), model.y(),
                        model.color(), false);
                graphics.text(font, model.arrow(), model.arrowX(), model.y(),
                        model.color(), false);
            }
        };
    }
}
