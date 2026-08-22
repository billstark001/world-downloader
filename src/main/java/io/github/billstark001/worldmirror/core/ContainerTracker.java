package io.github.billstark001.worldmirror.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.serialization.DataResult;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.download.DownloadManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

@Environment(EnvType.CLIENT)
public class ContainerTracker {
    private record ContainerKey(ResourceKey<Level> dimension, BlockPos pos) { }

    private record OpenContainer(
            BlockPos pos,
            Component name,
            Map<Integer, ItemStack> slots,
            int containerSize
    ) { }

    private record DoubleChestPositions(BlockPos first, BlockPos second) { }

    private static final Map<Integer, OpenContainer> openContainers = new ConcurrentHashMap<>();
    private static final Map<ContainerKey, CompoundTag> savedContainerData = new ConcurrentHashMap<>();

    public static void onContainerOpened(int syncId, Component name) {
        Minecraft client = Minecraft.getInstance();
        HitResult hitResult = client.hitResult;
        if (hitResult instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            openContainers.put(syncId, new OpenContainer(pos, name, new ConcurrentHashMap<>(), 0));
            WMLogger.debug("Container opened at " + pos + ": " + name.getString());
        }
    }

    public static void onSlotUpdate(int syncId, int slot, ItemStack stack) {
        OpenContainer container = openContainers.get(syncId);
        if (container == null || slot < 0) {
            return;
        }
        if (container.containerSize() <= 0 || slot >= container.containerSize()) {
            return;
        }

        if (stack.isEmpty()) {
            container.slots().remove(slot);
        } else {
            container.slots().put(slot, stack.copy());
        }

        ResourceKey<Level> dimension = currentDimension();
        if (container.containerSize() == 54
                && saveDoubleChestOverlay(dimension, container.pos(), container.name(), container.slots())) {
            return;
        }
        saveOverlay(dimension, container.pos(), container.name(), container.slots());
    }

    public static void onInventoryUpdate(int syncId, List<ItemStack> contents) {
        OpenContainer container = openContainers.get(syncId);
        if (container == null) {
            return;
        }

        int totalSlots = contents.size();
        int containerSlots = determineContainerSlots(totalSlots);

        if (containerSlots == 54) {
            handleDoubleChest(container, contents);
        } else {
            handleRegularContainer(container, contents, containerSlots);
        }
    }

    private static void handleRegularContainer(
            OpenContainer container,
            List<ItemStack> contents,
            int containerSlots) {
        ResourceKey<Level> dimension = currentDimension();
        if (dimension == null) {
            return;
        }

        Map<Integer, ItemStack> slots = copyContainerSlots(contents, containerSlots);
        openContainers.replaceAll((syncId, open) -> open == container
                ? new OpenContainer(open.pos(), open.name(), new ConcurrentHashMap<>(slots), containerSlots)
                : open);
        saveOverlay(dimension, container.pos(), container.name(), slots);

        int nonEmptySlots = slots.size();
        WMLogger.debug("Saved regular container at " + container.pos()
                + " with " + nonEmptySlots + "/" + containerSlots + " filled slots");
    }

    private static void handleDoubleChest(OpenContainer container, List<ItemStack> contents) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            handleRegularContainer(container, contents, 54);
            return;
        }

        BlockPos pos = container.pos();
        BlockState blockState = client.level.getBlockState(pos);
        if (!(blockState.getBlock() instanceof ChestBlock)) {
            handleRegularContainer(container, contents, 54);
            return;
        }

        try {
            ChestType chestType = blockState.getValue(ChestBlock.TYPE);
            ResourceKey<Level> dimension = client.level.dimension();

            if (chestType == ChestType.SINGLE) {
                handleRegularContainer(container, contents, 27);
                return;
            }

            DoubleChestPositions positions = doubleChestPositions(pos, blockState, chestType);

            Map<Integer, ItemStack> firstSlots = copyContainerSlots(
                    contents.subList(0, Math.min(27, contents.size())), 27);
            Map<Integer, ItemStack> secondSlots = copyContainerSlots(
                    contents.subList(Math.min(27, contents.size()), Math.min(54, contents.size())), 27);

            saveOverlay(dimension, positions.first(), container.name(), firstSlots);
            saveOverlay(dimension, positions.second(), container.name(), secondSlots);

            Map<Integer, ItemStack> combinedSlots = copyContainerSlots(contents, 54);
            openContainers.replaceAll((syncId, open) -> open == container
                    ? new OpenContainer(open.pos(), open.name(), new ConcurrentHashMap<>(combinedSlots), 54)
                    : open);

            WMLogger.debug("Saved double chest at " + positions.first() + "/"
                    + positions.second() + " with " + firstSlots.size() + "+"
                    + secondSlots.size() + " filled slots");
        } catch (Exception e) {
            WMLogger.warn("Failed to handle double chest, falling back: " + e.getMessage());
            handleRegularContainer(container, contents, 54);
        }
    }

    private static Map<Integer, ItemStack> copyContainerSlots(List<ItemStack> contents, int containerSlots) {
        Map<Integer, ItemStack> result = new HashMap<>();
        for (int i = 0; i < containerSlots && i < contents.size(); i++) {
            ItemStack stack = contents.get(i);
            if (!stack.isEmpty()) {
                result.put(i, stack.copy());
            }
        }
        return result;
    }

    private static boolean saveDoubleChestOverlay(
            ResourceKey<Level> dimension,
            BlockPos pos,
            Component name,
            Map<Integer, ItemStack> combinedSlots) {
        if (dimension == null || pos == null) {
            return false;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return false;
        }

        BlockState blockState = client.level.getBlockState(pos);
        if (!(blockState.getBlock() instanceof ChestBlock)) {
            return false;
        }

        ChestType chestType = blockState.getValue(ChestBlock.TYPE);
        if (chestType == ChestType.SINGLE) {
            return false;
        }

        DoubleChestPositions positions = doubleChestPositions(pos, blockState, chestType);
        saveOverlay(dimension, positions.first(), name, copySlotRange(combinedSlots, 0, 27));
        saveOverlay(dimension, positions.second(), name, copySlotRange(combinedSlots, 27, 27));
        return true;
    }

    private static DoubleChestPositions doubleChestPositions(
            BlockPos pos,
            BlockState blockState,
            ChestType chestType) {
        BlockPos connectedPos = ChestBlock.getConnectedBlockPos(pos, blockState);
        return chestType == ChestType.RIGHT
                ? new DoubleChestPositions(pos, connectedPos)
                : new DoubleChestPositions(connectedPos, pos);
    }

    private static Map<Integer, ItemStack> copySlotRange(
            Map<Integer, ItemStack> slots,
            int startSlot,
            int slotCount) {
        Map<Integer, ItemStack> result = new HashMap<>();
        for (Map.Entry<Integer, ItemStack> entry : slots.entrySet()) {
            int slot = entry.getKey();
            if (slot < startSlot || slot >= startSlot + slotCount) {
                continue;
            }

            ItemStack stack = entry.getValue();
            if (!stack.isEmpty()) {
                result.put(slot - startSlot, stack.copy());
            }
        }
        return result;
    }

    private static void saveOverlay(
            ResourceKey<Level> dimension,
            BlockPos pos,
            Component name,
            Map<Integer, ItemStack> slots) {
        if (dimension == null || pos == null) {
            return;
        }

        CompoundTag overlay = new CompoundTag();
        overlay.put("Items", serializeItems(slots));
        serializeCustomName(name).ifPresent(customName -> overlay.put("CustomName", customName));
        savedContainerData.put(new ContainerKey(dimension, pos), overlay);
        Minecraft client = Minecraft.getInstance();
        if (client.level != null && client.level.dimension().equals(dimension)) {
            DownloadManager.queueChunkCapture(client.level,
                    new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4), "container-update");
        }
    }

    private static ListTag serializeItems(Map<Integer, ItemStack> slots) {
        ListTag items = new ListTag();
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return items;
        }

        var ops = client.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for (Map.Entry<Integer, ItemStack> entry : slots.entrySet()) {
            ItemStack stack = entry.getValue();
            if (stack.isEmpty()) {
                continue;
            }

            DataResult<Tag> result = ItemStack.CODEC.encodeStart(ops, stack);
            result.resultOrPartial(error -> WMLogger.warnRateLimited(
                            "container-item-encode", 30_000L,
                            "Failed to encode a container item stack: " + error))
                    .ifPresent(tag -> {
                        if (tag instanceof CompoundTag itemNbt) {
                            itemNbt.putByte("Slot", entry.getKey().byteValue());
                            items.add(itemNbt);
                        }
                    });
        }
        return items;
    }

    private static java.util.Optional<Tag> serializeCustomName(Component name) {
        if (isDefaultContainerTitle(name)) {
            return java.util.Optional.empty();
        }

        return ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, name)
                .resultOrPartial(error -> WMLogger.warnRateLimited(
                        "container-name-encode", 30_000L,
                        "Failed to encode a container name: " + error));
    }

    private static boolean isDefaultContainerTitle(Component name) {
        if (name == null || name.getString().isEmpty()) {
            return true;
        }

        if (name.getContents() instanceof TranslatableContents translatable
                && translatable.getArgs().length == 0
                && name.getSiblings().isEmpty()
                && translatable.getKey().startsWith("container.")) {
            return true;
        }

        String text = name.getString();
        return "Chest".equals(text)
                || "Large Chest".equals(text);
    }

    private static int determineContainerSlots(int totalSlots) {
        return Math.max(0, totalSlots - 36);
    }

    public static void onContainerClosed(int syncId) {
        OpenContainer container = openContainers.remove(syncId);
        if (container != null) {
            WMLogger.debug("Container closed at " + container.pos());
        }
    }

    public static CompoundTag getContainerData(ResourceKey<Level> dimension, BlockPos pos) {
        if (dimension == null || pos == null) return null;
        return savedContainerData.get(new ContainerKey(dimension, pos));
    }

    public static Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> snapshotSavedData() {
        Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> snapshot = new HashMap<>();
        for (Map.Entry<ContainerKey, CompoundTag> entry : savedContainerData.entrySet()) {
            ContainerKey key = entry.getKey();
            snapshot.computeIfAbsent(key.dimension(), ignored -> new HashMap<>())
                    .put(key.pos(), entry.getValue().copy());
        }
        return snapshot;
    }

    public static boolean hasContainerData(ResourceKey<Level> dimension, BlockPos pos) {
        if (dimension == null || pos == null) return false;
        return savedContainerData.containsKey(new ContainerKey(dimension, pos));
    }

    public static void clear() {
        openContainers.clear();
        savedContainerData.clear();
        WMLogger.debug("Cleared all container data");
    }

    public static int getTotalSavedContainers() {
        return savedContainerData.size();
    }

    public static void evictForChunks(Map<ResourceKey<Level>, ? extends java.util.Collection<ChunkPos>> evictedByDim) {
        if (evictedByDim == null || evictedByDim.isEmpty()) return;

        Map<ResourceKey<Level>, java.util.Set<ChunkPos>> normalized = new java.util.HashMap<>();
        for (Map.Entry<ResourceKey<Level>, ? extends java.util.Collection<ChunkPos>> entry : evictedByDim.entrySet()) {
            java.util.Collection<ChunkPos> chunks = entry.getValue();
            if (chunks == null || chunks.isEmpty()) continue;
            normalized.put(entry.getKey(), chunks instanceof java.util.Set<?>
                    ? (java.util.Set<ChunkPos>) chunks
                    : new java.util.HashSet<>(chunks));
        }
        if (normalized.isEmpty()) return;

        int removed = 0;
        java.util.Iterator<ContainerKey> it = savedContainerData.keySet().iterator();
        while (it.hasNext()) {
            ContainerKey key = it.next();
            java.util.Set<ChunkPos> chunkSet = normalized.get(key.dimension());
            if (chunkSet != null && chunkSet.contains(new ChunkPos(key.pos().getX() >> 4, key.pos().getZ() >> 4))) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            WMLogger.debug("Evicted " + removed + " container entries across "
                    + normalized.size() + " dimension(s).");
        }
    }

    private static ResourceKey<Level> currentDimension() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null ? client.level.dimension() : null;
    }
}
