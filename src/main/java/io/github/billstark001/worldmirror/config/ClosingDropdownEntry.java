package io.github.billstark001.worldmirror.config;

import com.mojang.blaze3d.platform.InputConstants;
import me.shedaniel.clothconfig2.gui.entries.DropdownBoxEntry;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Cloth dropdown that deterministically closes after selection, focus loss, or Escape. */
@SuppressWarnings("deprecation")
final class ClosingDropdownEntry<T> extends DropdownBoxEntry<T> {
    ClosingDropdownEntry(
            Component fieldName,
            Component resetButtonKey,
            Supplier<Optional<Component[]>> tooltipSupplier,
            Supplier<T> defaultValue,
            Consumer<T> saveConsumer,
            Iterable<T> selections,
            SelectionTopCellElement<T> topCell,
            SelectionCellCreator<T> cellCreator) {
        super(fieldName, resetButtonKey, tooltipSupplier, false, defaultValue, saveConsumer,
                selections, topCell, cellCreator);
        setSuggestionMode(false);
    }

    @Override
    public void setFocused(GuiEventListener listener) {
        super.setFocused(listener);
        if (listener != selectionElement) updateSelected(false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        boolean expanded = getMorePossibleHeight() >= 0;
        if (expanded && !selectionElement.isMouseOver(click.x(), click.y())) {
            closeMenu();
        }
        boolean handled = super.mouseClicked(click, doubled);
        if (expanded && handled) closeMenu();
        return handled;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (getMorePossibleHeight() >= 0 && input.key() == InputConstants.KEY_ESCAPE) {
            closeMenu();
            return true;
        }
        return super.keyPressed(input);
    }

    private void closeMenu() {
        updateSelected(false);
        super.setFocused(null);
    }
}
