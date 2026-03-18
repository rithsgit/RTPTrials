package com.example;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import net.minecraft.core.BlockPos;

/**
 * Encapsulates the used-position history with automatic cap-based eviction.
 * Thread-safe — backed by ConcurrentHashMap and ConcurrentLinkedDeque.
 */
public class PositionHistory {

    private final int cap;
    private final Set<BlockPos> positions = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedDeque<BlockPos> order = new ConcurrentLinkedDeque<>();

    public PositionHistory(int cap) {
        this.cap = cap;
    }

    /** Records a position, evicting the oldest entry if the cap is exceeded. */
    public void record(BlockPos pos) {
        positions.add(pos);
        order.addLast(pos);
        if (order.size() > cap) {
            BlockPos oldest = order.pollFirst();
            if (oldest != null) positions.remove(oldest);
        }
    }

    /**
     * Returns true if {@code pos} is within {@code minDistanceSq} squared blocks
     * of any recorded position.
     */
    public boolean isTooClose(BlockPos pos, long minDistanceSq) {
        for (BlockPos used : positions) {
            long dx = pos.getX() - used.getX();
            long dz = pos.getZ() - used.getZ();
            if (dx * dx + dz * dz < minDistanceSq) return true;
        }
        return false;
    }

    /** Clears all recorded positions. */
    public void clear() {
        positions.clear();
        order.clear();
    }

    public int size() {
        return positions.size();
    }
}