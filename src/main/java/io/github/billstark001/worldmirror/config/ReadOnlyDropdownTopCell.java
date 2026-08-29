package io.github.billstark001.worldmirror.config;

import me.shedaniel.clothconfig2.gui.entries.DropdownBoxEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Version-neutral state, focus, layout, and truncation for read-only dropdown top cells. */
abstract class ReadOnlyDropdownTopCell<T>
        extends DropdownBoxEntry.SelectionTopCellElement<T> {
    private static final int HORIZONTAL_PADDING = 5;
    private static final int LABEL_ARROW_GAP = 2;

    private final Function<T, Component> label;
    private final Button focusTarget;
    private final T initialValue;
    private T value;

    protected ReadOnlyDropdownTopCell(T value, Function<T, Component> label) {
        this.initialValue = value;
        this.value = value;
        this.label = label;
        this.focusTarget = Button.builder(label.apply(value), ignored -> { })
                .bounds(0, 0, 0, 0).build();
    }

    @Override public T getValue() { return value; }

    @Override
    public void setValue(T value) {
        this.value = value;
        focusTarget.setMessage(label.apply(value));
    }

    @Override public Component getSearchTerm() { return label.apply(value); }
    @Override public Optional<Component> getError() { return Optional.empty(); }

    @Override
    public boolean isEdited() {
        return super.isEdited() || !Objects.equals(initialValue, value);
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return Collections.singletonList(focusTarget);
    }

    /** Computes the complete render model; target adapters only submit its two text draws. */
    protected final RenderModel layout(int x, int y, int width, int height) {
        focusTarget.setX(x);
        focusTarget.setY(y);
        focusTarget.setWidth(width);
        focusTarget.setHeight(height);

        var font = Minecraft.getInstance().font;
        boolean expanded = getParent() != null && getParent().getMorePossibleHeight() >= 0;
        String arrow = expanded ? "▴" : "▾";
        int arrowWidth = font.width(arrow);
        int labelWidth = width - HORIZONTAL_PADDING * 2 - LABEL_ARROW_GAP - arrowWidth;
        Component text = fitLabel(label.apply(value), labelWidth);
        int textY = y + Math.max(0, (height - font.lineHeight) / 2);
        int arrowX = x + width - HORIZONTAL_PADDING - arrowWidth;
        return new RenderModel(text, arrow, x + HORIZONTAL_PADDING, arrowX,
                textY, getPreferredTextColor());
    }

    private static Component fitLabel(Component label, int maximumWidth) {
        var font = Minecraft.getInstance().font;
        if (maximumWidth <= 0) return Component.empty();
        if (font.width(label) <= maximumWidth) return label;
        String ellipsis = "…";
        int ellipsisWidth = font.width(ellipsis);
        if (maximumWidth < ellipsisWidth) return Component.empty();
        return Component.literal(font.plainSubstrByWidth(
                label.getString(), maximumWidth - ellipsisWidth)).append(ellipsis);
    }

    protected record RenderModel(
            Component text, String arrow, int textX, int arrowX, int y, int color) { }
}
