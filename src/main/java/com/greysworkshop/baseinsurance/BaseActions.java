package com.greysworkshop.baseinsurance;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Accepting changes into the snapshot (with undo) and marking block types as "not damage". */
public final class BaseActions {
	private static final int MAX_UNDO = 20;

	/** Everything needed to put an accept back exactly as it was. */
	private record Undo(String description, Map<Long, BlockState> baseline, Map<Long, Base.Change> changes,
			Map<Long, Base.ContainerSnapshot> containers, String ignoredBlock) {}

	/** Undo history per base, for this session. */
	private static final Map<Base, Deque<Undo>> history = new WeakHashMap<>();

	private BaseActions() {}

	/** Makes the current state of these changed blocks the new normal. Returns how many were accepted. */
	public static int accept(Base base, Collection<Long> keys, String description) {
		return accept(base, keys, description, null);
	}

	private static int accept(Base base, Collection<Long> keys, String description, String ignoredBlock) {
		Map<Long, BlockState> oldBaseline = new HashMap<>();
		Map<Long, Base.Change> oldChanges = new HashMap<>();
		Map<Long, Base.ContainerSnapshot> oldContainers = new HashMap<>();
		for (long key : new ArrayList<>(keys)) {
			Base.Change change = base.changes.get(key);
			if (change == null) continue;
			BlockPos pos = BlockPos.fromLong(key);
			oldBaseline.put(key, base.baseline.get(pos));
			oldChanges.put(key, change);
			BlockState now = change.nowState();
			if (now != null) base.baseline.set(pos, now);
			if (change.kind() != Base.Kind.ADDED) {
				Base.ContainerSnapshot container = base.containers.remove(key);
				if (container != null) oldContainers.put(key, container);
			}
			base.changes.remove(key);
		}
		if (oldChanges.isEmpty() && ignoredBlock == null) return 0;
		base.baselineDirty = true;
		base.log(Base.EventType.ACCEPTED, description + " (" + oldChanges.size() + " block" + (oldChanges.size() == 1 ? "" : "s") + ")");
		Deque<Undo> undo = history.computeIfAbsent(base, b -> new ArrayDeque<>());
		undo.push(new Undo(description, oldBaseline, oldChanges, oldContainers, ignoredBlock));
		while (undo.size() > MAX_UNDO) undo.removeLast();
		return oldChanges.size();
	}

	/** Accepts every current change. */
	public static int acceptAll(Base base) {
		return accept(base, new ArrayList<>(base.changes.keySet()), "Accepted all changes");
	}

	/** Accepts the changes within {@code radius} of {@code center}. */
	public static int acceptNear(Base base, BlockPos center, int radius) {
		List<Long> keys = new ArrayList<>();
		for (long key : base.changes.keySet()) {
			if (BlockPos.fromLong(key).isWithinDistance(center, radius)) keys.add(key);
		}
		return accept(base, keys, "Accepted changes within " + radius + " blocks of " + Alerts.pos(center));
	}

	/**
	 * "Not damage": ignores this block type from now on (in every base) and accepts these changes to it.
	 * Undoing it un-ignores the block too.
	 */
	public static int ignoreType(Base base, String blockId, String blockName, Collection<Long> keys) {
		boolean added = !BIConfig.INSTANCE.ignoredBlocks.contains(blockId);
		if (added) {
			BIConfig.INSTANCE.ignoredBlocks.add(blockId);
			BIConfig.save();
		}
		return accept(base, keys, "Marked " + blockName + " as not damage", added ? blockId : null);
	}

	public static boolean canUndo(Base base) {
		Deque<Undo> undo = history.get(base);
		return undo != null && !undo.isEmpty();
	}

	public static String undoDescription(Base base) {
		return canUndo(base) ? history.get(base).peek().description() : "";
	}

	/** Puts the last accept back: snapshot values, change records, container records and any ignored block. */
	public static boolean undo(Base base) {
		if (!canUndo(base)) return false;
		Undo undo = history.get(base).pop();
		for (Map.Entry<Long, BlockState> entry : undo.baseline().entrySet()) {
			if (entry.getValue() != null) base.baseline.set(BlockPos.fromLong(entry.getKey()), entry.getValue());
		}
		base.changes.putAll(undo.changes());
		base.containers.putAll(undo.containers());
		if (undo.ignoredBlock() != null) {
			BIConfig.INSTANCE.ignoredBlocks.remove(undo.ignoredBlock());
			BIConfig.save();
		}
		base.baselineDirty = true;
		base.log(Base.EventType.ACCEPTED, "Undid: " + undo.description());
		return true;
	}
}
