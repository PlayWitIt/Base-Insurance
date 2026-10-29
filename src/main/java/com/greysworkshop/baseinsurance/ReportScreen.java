package com.greysworkshop.baseinsurance;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The damage report. Every group of changes can be found (markers through walls), accepted, or marked as
 * "never count" (ignore that block type), all undoable.
 */
public class ReportScreen extends Screen {
	private enum Tab { OVERVIEW, DAMAGE, CONTAINERS, REBUILD, TIMELINE }

	private static final int PAD = 8;
	private static final int TOP = 54;
	private static final int TEXT_HEIGHT = 11;
	private static final int ICON_HEIGHT = 20;

	private static final int PANEL = 0xD0101018;
	private static final int BORDER = 0xFF33334D;
	private static final int WHITE = 0xFFF0F0F0;
	private static final int GRAY = 0xFF9090A8;
	private static final int GOLD = 0xFFFFC040;
	private static final int GREEN = 0xFF55FF55;
	private static final int RED = 0xFFFF5555;
	private static final int YELLOW = 0xFFFFE060;
	private static final int PURPLE = 0xFFFF77FF;
	private static final int CYAN = 0xFF40E0FF;
	private static final int ACCEPT = 0xFF55CC55;
	private static final int NOT_DAMAGE = 0xFFFFAA40;

	private static Tab lastTab = Tab.OVERVIEW;

	/** A clickable chip at the right of a row, with a line saying what it does. */
	private record Action(String label, int color, Runnable run, String tip) {
		Action(String label, int color, Runnable run) {
			this(label, color, run, null);
		}
	}

	/** One entry; {@code bar} (0 to 1) draws a progress bar under it, -1 for none. */
	private record Line(Text text, ItemStack icon, int color, int indent, boolean header, List<Action> actions, float bar) {
		Line(Text text, ItemStack icon, int color, int indent, boolean header, List<Action> actions) {
			this(text, icon, color, indent, header, actions, -1);
		}
	}

	private record Row(OrderedText text, ItemStack icon, int color, int indent, boolean header, List<Action> actions, int height, float bar) {}

	private record Hit(int x0, int y0, int x1, int y1, Runnable run, String tip) {}

	private static final String TIP_SHOW = "Puts markers on it that show through walls, and points the way";
	private static final String TIP_FINE = "It was you, or it's fine: take it as the new normal and stop reporting it (Undo can bring it back)";

	private final Base base;
	private Tab tab = lastTab;
	private final List<Row> rows = new ArrayList<>();
	private final List<Hit> hits = new ArrayList<>();
	private int contentHeight;
	private double scroll;
	private String status;
	private long statusUntil;

	public ReportScreen(Base base) {
		super(Text.translatable("baseinsurance.report.title", base.name));
		this.base = base;
	}

	// ---- Widgets ----

	@Override
	protected void init() {
		Tab[] tabs = Tab.values();
		int tabWidth = Math.min(84, (width - 2 * PAD) / tabs.length - 4);
		int x = width / 2 - (tabs.length * (tabWidth + 4) - 4) / 2;
		for (Tab t : tabs) {
			Text label = Text.translatable("baseinsurance.report.tab." + t.name().toLowerCase());
			if (t == Tab.DAMAGE && base.damageCount() > 0) label = label.copy().append(" (" + base.damageCount() + ")");
			ButtonWidget button = ButtonWidget.builder(label, b -> showTab(t.name())).dimensions(x, 28, tabWidth, 20).build();
			button.active = t != tab;
			addDrawableChild(button);
			x += tabWidth + 4;
		}

		int y = height - 26;
		int w = Math.min(100, (width - 2 * PAD - 12) / 4);
		int left = width / 2 - (4 * w + 12) / 2;
		addDrawableChild(ButtonWidget.builder(overlayLabel(), b -> {
			Overlay.enabled = !Overlay.enabled;
			b.setMessage(overlayLabel());
		}).dimensions(left, y, w, 20).build());
		ButtonWidget undo = ButtonWidget.builder(Text.translatable("baseinsurance.report.undo"), b -> {
			String what = BaseActions.undoDescription(base);
			if (BaseActions.undo(base)) flash("Undid: " + what);
			clearAndInit();
		}).dimensions(left + w + 4, y, w, 20).build();
		undo.active = BaseActions.canUndo(base);
		if (undo.active) undo.setTooltip(Tooltip.of(Text.literal("Undo: " + BaseActions.undoDescription(base))));
		addDrawableChild(undo);
		ButtonWidget acceptAll = ButtonWidget.builder(Text.translatable("baseinsurance.report.accept"), b ->
				client.setScreen(new ConfirmScreen(confirmed -> {
					if (confirmed) flash("Marked " + Alerts.plural(BaseActions.acceptAll(base), "change") + " as fine · Undo is available");
					client.setScreen(this);
				}, Text.translatable("baseinsurance.report.accept.title"),
						Text.translatable("baseinsurance.report.accept.message", base.damageCount(), base.name))))
				.dimensions(left + 2 * (w + 4), y, w, 20).build();
		acceptAll.active = base.damageCount() > 0;
		addDrawableChild(acceptAll);
		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close()).dimensions(left + 3 * (w + 4), y, w, 20).build());

		rebuildRows();
	}

	private static Text overlayLabel() {
		return ScreenTexts.composeToggleText(Text.translatable("baseinsurance.report.overlay"), Overlay.enabled);
	}

	/** Switches tab by name, e.g. "DAMAGE". */
	public void showTab(String name) {
		tab = Tab.valueOf(name);
		lastTab = tab;
		scroll = 0;
		clearAndInit();
	}

	private void flash(String message) {
		status = message;
		statusUntil = System.currentTimeMillis() + 5000;
	}

	private void rebuildRows() {
		List<Line> lines = switch (tab) {
			case OVERVIEW -> overview();
			case DAMAGE -> damage();
			case CONTAINERS -> containers();
			case REBUILD -> rebuild();
			case TIMELINE -> timeline();
		};
		rows.clear();
		contentHeight = 0;
		int textWidth = width - 4 * PAD;
		for (Line line : lines) {
			int actionsWidth = 0;
			for (Action action : line.actions) actionsWidth += textRenderer.getWidth(action.label) + 12;
			int iconSpace = line.icon.isEmpty() ? 0 : 20;
			List<OrderedText> wrapped = textRenderer.wrapLines(line.text, Math.max(40, textWidth - line.indent - iconSpace - actionsWidth));
			if (wrapped.isEmpty()) wrapped = List.of(OrderedText.EMPTY);
			for (int i = 0; i < wrapped.size(); i++) {
				boolean first = i == 0;
				int height = first && (!line.icon.isEmpty() || !line.actions.isEmpty()) ? ICON_HEIGHT : TEXT_HEIGHT;
				if (line.header && first) height = 20;
				float bar = i == wrapped.size() - 1 ? line.bar : -1;
				if (bar >= 0) height += 8;
				rows.add(new Row(wrapped.get(i), first ? line.icon : ItemStack.EMPTY, line.color,
						line.indent + (first ? 0 : iconSpace), line.header && first, first ? line.actions : List.of(), height, bar));
				contentHeight += height;
			}
		}
		scroll = Math.min(scroll, maxScroll());
	}

	// ---- Actions ----

	private void find(List<Long> keys, String what, ItemStack icon) {
		List<BlockPos> positions = new ArrayList<>();
		for (long key : keys) positions.add(BlockPos.fromLong(key));
		DamageLocator.guide(base.dimension, positions, what, icon);
		Overlay.enabled = true;
		close();
	}

	private void findPos(BlockPos pos, String what, ItemStack icon) {
		DamageLocator.guide(base.dimension, List.of(pos), what, icon);
		close();
	}

	private void acceptGroup(List<Long> keys, String what) {
		int n = BaseActions.accept(base, keys, "Accepted " + what);
		flash("Marked " + Alerts.plural(n, "change") + " as fine · Undo is available");
		clearAndInit();
	}

	/** Ignores a block type from now on, and accepts every current change involving it. */
	private void notDamage(String blockId) {
		String blockName = BlockStates.name(blockId).getString();
		List<Long> involving = new ArrayList<>();
		for (Map.Entry<Long, Base.Change> e : base.changes.entrySet()) {
			if (involves(e.getValue().wasState(), blockId) || involves(e.getValue().nowState(), blockId)) involving.add(e.getKey());
		}
		int accepted = BaseActions.ignoreType(base, blockId, blockName, involving);
		flash(blockName + " never counts now (" + accepted + " cleared) · Undo is available");
		clearAndInit();
	}

	private static boolean involves(BlockState state, String blockId) {
		return state != null && Registries.BLOCK.getId(state.getBlock()).toString().equals(blockId);
	}

	// ---- Tabs ----

	private List<Line> overview() {
		List<Line> lines = new ArrayList<>();
		lines.add(new Line(Text.literal(base.name + "  ·  " + base.sizeText() + " blocks, " + base.dimension.replace("minecraft:", "").replace('_', ' ')),
				ItemStack.EMPTY, GOLD, 0, true, List.of(
						new Action("Map", CYAN, () -> client.setScreen(MapScreen.centeredOn(base)), "Shows the base on the map"),
						new Action("Resize", CYAN, () -> client.setScreen(MapScreen.forEdit(base)), "Make the base bigger or smaller, or move it"))));
		lines.add(text("From " + base.minX + " " + base.minY + " " + base.minZ + " to " + base.maxX + " " + base.maxY + " " + base.maxZ
				+ " · insured " + Alerts.time(base.created) + " · last visit " + Alerts.ago(base.lastSeen), GRAY));
		lines.add(blank());

		int percent = base.snapshotPercent();
		if (base.damageCount() == 0) {
			lines.add(header(percent < 100 ? "✔ Nothing has changed in the parts checked so far" : "✔ Nothing has changed", GREEN));
			lines.add(text("Everything Base Insurance has seen here is just as it was.", GRAY));
		} else {
			int live = 0;
			for (Base.Change change : base.changes.values()) if (change.live) live++;
			int count = base.damageCount();
			lines.add(new Line(Text.literal("⚠ " + count + (count == 1 ? " thing has" : " things have") + " changed"), ItemStack.EMPTY, RED, 0, true,
					List.of(new Action("See all", RED, () -> showTab("DAMAGE"), "Every change, grouped, with what you can do about each"))));
			// Most important first: containers broken, then what's gone, swapped, added, and frames.
			List<Runnable> rows = new ArrayList<>();
			for (long key : destroyedContainers()) {
				Base.ContainerSnapshot snapshot = base.containers.get(key);
				BlockPos pos = BlockPos.fromLong(key);
				ItemStack icon = BlockStates.icon(BlockStates.parse(snapshot.block));
				String had = snapshot.items.isEmpty() ? "it was empty" : "had " + String.join(", ", ContainerWatcher.describe(snapshot.items, 3));
				rows.add(() -> lines.add(new Line(Text.literal(BlockStates.name(snapshot.block).getString() + " destroyed · " + had), icon, RED, 8, false,
						List.of(new Action("Show me", CYAN, () -> findPos(pos, "Destroyed container", icon), TIP_SHOW)))));
			}
			java.util.Set<Long> destroyedHere = new java.util.HashSet<>(destroyedContainers());
			for (Group group : groups()) {
				// A destroyed chest is already listed above, with what it held.
				if (destroyedHere.containsAll(group.keys)) continue;
				ItemStack icon = BlockStates.icon(group.icon);
				rows.add(() -> lines.add(new Line(Text.literal(group.sentence()), icon, 0xFF000000 | Overlay.color(group.kind), 8, false,
						List.of(new Action("Show me", CYAN, () -> find(group.keys, group.name, icon), TIP_SHOW)))));
			}
			for (Base.EntityChange change : base.entityChanges.values()) {
				ItemStack icon = entityIcon(change.was);
				rows.add(() -> lines.add(new Line(Text.literal(change.describe()), icon, 0xFF000000 | Overlay.ENTITY, 8, false,
						List.of(new Action("Show me", CYAN, () -> findPos(change.was.pos(), change.was.name, icon), TIP_SHOW)))));
			}
			rows.subList(0, Math.min(6, rows.size())).forEach(Runnable::run);
			if (rows.size() > 6) lines.add(indented("…and " + (rows.size() - 6) + " more. See all shows everything.", GRAY, 8));
			if (!base.changes.isEmpty()) {
				lines.add(indented((base.changes.size() - live) + " found when you came back · " + live + " while you were here", GRAY, 8));
			}
		}
		lines.add(blank());

		// Chests and frames that looked gone from out of sight, with no word from the server that they were broken.
		var unseenBlocks = BaseInsuranceClient.INSTANCE.scanner.toCheck(base);
		var unseenEntities = BaseInsuranceClient.INSTANCE.entityWatch.toCheck(base);
		if (!unseenBlocks.isEmpty() || !unseenEntities.isEmpty()) {
			lines.add(header("◎ Can't see from here: " + (unseenBlocks.size() + unseenEntities.size()) + " to look at", YELLOW));
			lines.add(indented("Servers hide chests and item frames you can't see, so these aren't counted as damage. Go and look: "
					+ "if one is really gone it's reported then. (One broken where the server says so is reported straight away.)", GRAY, 10));
			unseenBlocks.entrySet().stream().limit(6).forEach(e -> {
				BlockPos pos = BlockPos.fromLong(e.getKey());
				ItemStack icon = BlockStates.icon(e.getValue());
				String what = e.getValue().getBlock().getName().getString();
				lines.add(new Line(Text.literal(what + " at " + Alerts.pos(pos)), icon, WHITE, 8, false,
						List.of(new Action("Show me", CYAN, () -> findPos(pos, what, icon), TIP_SHOW))));
			});
			unseenEntities.values().stream().limit(6).forEach(record -> {
				ItemStack icon = entityIcon(record);
				lines.add(new Line(Text.literal(record.name + " (" + record.contents() + ") at " + Alerts.pos(record.pos())), icon, WHITE, 8, false,
						List.of(new Action("Show me", CYAN, () -> findPos(record.pos(), record.name, icon), TIP_SHOW))));
			});
			lines.add(blank());
		}

		lines.add(new Line(Text.literal(percent < 100 ? "◔ Checked so far: " + percent + "%" : "● Fully checked"), ItemStack.EMPTY,
				percent < 100 ? YELLOW : GREEN, 0, false,
				percent < 100 ? List.of(new Action("Where?", CYAN, () -> client.setScreen(MapScreen.centeredOn(base)),
						"The map shades the parts still to check")) : List.of(), percent / 100f));
		lines.add(indented(percent < 100
				? "Everything within " + BIConfig.INSTANCE.trustRadius + " blocks of you is checked (further off, servers can fake blocks). Walk past the shaded parts on the map once and the whole base is covered."
				: "Every part of the base has been checked, so a change anywhere in it will be noticed.", GRAY, 10));
		lines.add(blank());

		int destroyed = destroyedContainers().size();
		long thefts = base.events.stream().filter(e -> e.type == Base.EventType.CONTAINER).count();
		lines.add(new Line(Text.literal("▣ Chests on record: " + base.containers.size()
				+ (destroyed > 0 ? " · " + destroyed + " destroyed" : "") + (thefts > 0 ? " · " + thefts + " had items taken" : "")),
				ItemStack.EMPTY, destroyed + thefts > 0 ? RED : WHITE, 0, false,
				List.of(new Action("Chests", CYAN, () -> showTab("CONTAINERS"), "Every chest, barrel and shulker box on record"))));
		if (base.containers.isEmpty()) lines.add(indented("Open each chest once so Base Insurance knows what's in it.", GRAY, 10));
		lines.add(blank());

		List<Base.Event> sightings = base.events.stream().filter(e -> e.type == Base.EventType.PLAYER).toList();
		lines.add(text("◉ Players seen near here: " + (sightings.isEmpty() ? "nobody" : sightings.size()), sightings.isEmpty() ? GREEN : PURPLE));
		for (int i = sightings.size() - 1; i >= Math.max(0, sightings.size() - 5); i--) {
			lines.add(indented(Alerts.ago(sightings.get(i).time) + ": " + sightings.get(i).text, GRAY, 10));
		}
		return lines;
	}

	/** Changes of one kind to one block type. */
	private record Group(Base.Kind kind, String name, BlockState icon, List<Long> keys, int nearest) {
		String sentence() {
			String what = name + (keys.size() > 1 ? " ×" + keys.size() : "");
			String verb = switch (kind) {
				case REMOVED -> " gone";
				case ADDED -> " added by someone else";
				case REPLACED -> "";
			};
			return what + verb + (nearest >= 0 ? " · nearest " + nearest + "m" : "");
		}
	}

	/** Block changes grouped by kind and block, most important kind first, biggest groups first. */
	private List<Group> groups() {
		MinecraftClient mc = MinecraftClient.getInstance();
		BlockPos me = mc.player != null && base.dimension.equals(BaseManager.dimensionKey(mc.world)) ? mc.player.getBlockPos() : null;
		List<Group> result = new ArrayList<>();
		for (Base.Kind kind : List.of(Base.Kind.REMOVED, Base.Kind.REPLACED, Base.Kind.ADDED)) {
			Map<String, List<Long>> byName = new LinkedHashMap<>();
			Map<String, BlockState> icons = new LinkedHashMap<>();
			for (Map.Entry<Long, Base.Change> entry : base.changes.entrySet()) {
				Base.Change change = entry.getValue();
				if (change.kind() != kind) continue;
				String name = switch (kind) {
					case REMOVED -> BlockStates.name(change.was).getString();
					case ADDED -> BlockStates.name(change.now).getString();
					case REPLACED -> BlockStates.name(change.was).getString() + " → " + BlockStates.name(change.now).getString();
				};
				byName.computeIfAbsent(name, k -> new ArrayList<>()).add(entry.getKey());
				icons.putIfAbsent(name, kind == Base.Kind.ADDED ? change.nowState() : change.wasState());
			}
			byName.entrySet().stream()
					.sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
					.forEach(e -> result.add(new Group(kind, e.getKey(), icons.get(e.getKey()), e.getValue(), me == null ? -1
							: (int) Math.sqrt(e.getValue().stream().mapToDouble(k -> BlockPos.fromLong(k).getSquaredDistance(me)).min().orElse(0)))));
		}
		return result;
	}

	/** Changes grouped by kind and block, each group with Show me / It's fine / Never count. */
	private List<Line> damage() {
		List<Line> lines = new ArrayList<>();
		if (base.damageCount() == 0) {
			lines.add(text("✔ Nothing has changed.", GREEN));
			return lines;
		}
		lines.add(text("Only real changes are listed: Base Insurance ignores what servers fake (hidden ores, chests and frames out of sight, blocks still loading). "
				+ "Show me marks it through walls. It's fine stops reporting it. Never count ignores that kind of block from now on. Everything can be undone.", GRAY));
		lines.add(blank());

		MinecraftClient mc = MinecraftClient.getInstance();
		BlockPos me = mc.player != null && base.dimension.equals(BaseManager.dimensionKey(mc.world)) ? mc.player.getBlockPos() : null;

		for (Base.Kind kind : Base.Kind.values()) {
			Map<String, List<Long>> groups = new LinkedHashMap<>();
			Map<String, BlockState> icons = new LinkedHashMap<>();
			for (Map.Entry<Long, Base.Change> entry : base.changes.entrySet()) {
				Base.Change change = entry.getValue();
				if (change.kind() != kind) continue;
				String name = switch (kind) {
					case REMOVED -> BlockStates.name(change.was).getString();
					case ADDED -> BlockStates.name(change.now).getString();
					case REPLACED -> BlockStates.name(change.was).getString() + " → " + BlockStates.name(change.now).getString();
				};
				groups.computeIfAbsent(name, k -> new ArrayList<>()).add(entry.getKey());
				icons.putIfAbsent(name, kind == Base.Kind.REMOVED ? change.wasState() : change.nowState());
			}
			if (groups.isEmpty()) continue;
			int total = groups.values().stream().mapToInt(List::size).sum();
			String title = switch (kind) {
				case REMOVED -> "Gone";
				case ADDED -> "Added by someone else";
				case REPLACED -> "Swapped for something else";
			};
			List<Long> allOfKind = new ArrayList<>();
			groups.values().forEach(allOfKind::addAll);
			lines.add(new Line(Text.literal(title + " (" + total + ")"), ItemStack.EMPTY, 0xFF000000 | Overlay.color(kind), 0, true,
					List.of(new Action("Show all", CYAN, () -> find(allOfKind, title, ItemStack.EMPTY), TIP_SHOW),
							new Action("All fine", ACCEPT, () -> acceptGroup(allOfKind, "all " + title.toLowerCase()), TIP_FINE))));
			groups.entrySet().stream()
					.sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
					.forEach(group -> {
						String name = group.getKey();
						List<Long> keys = group.getValue();
						BlockState shown = icons.get(name);
						ItemStack icon = BlockStates.icon(shown);
						String nearest = me == null ? "" : "  · nearest " + (int) Math.sqrt(keys.stream()
								.mapToDouble(k -> BlockPos.fromLong(k).getSquaredDistance(me)).min().orElse(0)) + "m";
						List<Action> actions = new ArrayList<>();
						actions.add(new Action("Show me", CYAN, () -> find(keys, name, icon), TIP_SHOW));
						actions.add(new Action("It's fine", ACCEPT, () -> acceptGroup(keys, name), TIP_FINE));
						// "Never count" ignores the block that's there now (or, for removals, the one that went missing).
						if (shown != null && !shown.isAir()) {
							String blockId = Registries.BLOCK.getId(shown.getBlock()).toString();
							String blockName = shown.getBlock().getName().getString();
							actions.add(new Action("Never count", NOT_DAMAGE, () -> notDamage(blockId),
									"Changes to " + blockName + " never count from now on (Settings → Detection lists them)"));
						}
						lines.add(new Line(Text.empty().append(Text.literal(name + " ×" + keys.size()).styled(s -> s.withColor(WHITE)))
								.append(Text.literal(nearest).styled(s -> s.withColor(GRAY))), icon, WHITE, 8, false, actions));
					});
			lines.add(blank());
		}

		if (!base.entityChanges.isEmpty()) {
			List<String> allEntities = new ArrayList<>(base.entityChanges.keySet());
			lines.add(new Line(Text.literal("Item frames, paintings & armor stands (" + allEntities.size() + ")"), ItemStack.EMPTY,
					0xFF000000 | Overlay.ENTITY, 0, true,
					List.of(new Action("All fine", ACCEPT, () -> {
						flash("Marked " + BaseActions.acceptEntities(base, allEntities, "Accepted all entity changes") + " as fine · Undo is available");
						clearAndInit();
					}, TIP_FINE))));
			base.entityChanges.entrySet().stream()
					.sorted(Comparator.comparingLong(e -> -e.getValue().firstSeen))
					.forEach(e -> {
						String key = e.getKey();
						Base.EntityChange change = e.getValue();
						ItemStack icon = entityIcon(change.was);
						String text = change.describe() + " at " + Alerts.pos(change.was.pos()) + " · "
								+ (change.live ? "while you were here" : "found when you came back") + ", " + Alerts.ago(change.firstSeen);
						lines.add(new Line(Text.literal(text), icon, WHITE, 8, false, List.of(
								new Action("Show me", CYAN, () -> findPos(change.was.pos(), change.was.name, icon), TIP_SHOW),
								new Action("It's fine", ACCEPT, () -> {
									BaseActions.acceptEntities(base, List.of(key), "Accepted " + change.describe());
									flash("Marked as fine: " + change.describe() + " · Undo is available");
									clearAndInit();
								}, TIP_FINE))));
					});
			lines.add(blank());
		}

		if (me != null && !base.changes.isEmpty()) {
			lines.add(header("Nearest to you", GOLD));
			base.changes.entrySet().stream()
					.sorted(Comparator.comparingDouble(e -> BlockPos.fromLong(e.getKey()).getSquaredDistance(me)))
					.limit(15)
					.forEach(e -> {
						BlockPos pos = BlockPos.fromLong(e.getKey());
						Base.Change change = e.getValue();
						String what = switch (change.kind()) {
							case REMOVED -> BlockStates.name(change.was).getString() + " gone";
							case ADDED -> BlockStates.name(change.now).getString() + " added";
							case REPLACED -> BlockStates.name(change.was).getString() + " is now " + BlockStates.name(change.now).getString();
						};
						ItemStack icon = BlockStates.icon(change.kind() == Base.Kind.ADDED ? change.nowState() : change.wasState());
						lines.add(new Line(Text.literal(what + " at " + Alerts.pos(pos) + " (" + (int) Math.sqrt(pos.getSquaredDistance(me)) + "m) · "
								+ (change.live ? "while you were here" : "found when you came back") + ", " + Alerts.ago(change.firstSeen)), icon,
							0xFF000000 | Overlay.color(change.kind()), 8, false,
							List.of(new Action("Show me", CYAN, () -> findPos(pos, what, icon), TIP_SHOW),
									new Action("It's fine", ACCEPT, () -> acceptGroup(List.of(e.getKey()), what), TIP_FINE))));
					});
		}
		return lines;
	}

	private List<Line> containers() {
		List<Line> lines = new ArrayList<>();
		List<Long> destroyed = destroyedContainers();
		if (!destroyed.isEmpty()) {
			lines.add(header("✖ Destroyed (" + destroyed.size() + ")", RED));
			for (long key : destroyed) {
				Base.ContainerSnapshot snapshot = base.containers.get(key);
				BlockPos pos = BlockPos.fromLong(key);
				ItemStack icon = BlockStates.icon(BlockStates.parse(snapshot.block));
				lines.add(new Line(Text.literal(BlockStates.name(snapshot.block).getString() + " at " + Alerts.pos(pos)
						+ " · last contents (" + Alerts.time(snapshot.updated) + "):"), icon, WHITE, 8, false,
						List.of(new Action("Show me", CYAN, () -> findPos(pos, "Destroyed container", icon), TIP_SHOW))));
				addItems(lines, snapshot.items, 8, 28);
			}
			lines.add(blank());
		}

		List<Base.Event> thefts = base.events.stream().filter(e -> e.type == Base.EventType.CONTAINER).toList();
		lines.add(header("⚠ Items taken (" + thefts.size() + ")", thefts.isEmpty() ? GREEN : RED));
		lines.add(indented(thefts.isEmpty() ? "Nothing missing from any chest you've opened."
				: "Each time you open a chest it's checked against how you left it, once its contents have fully arrived.", GRAY, 10));
		for (int i = thefts.size() - 1; i >= 0; i--) {
			lines.add(indented("[" + Alerts.time(thefts.get(i).time) + "] " + thefts.get(i).text, YELLOW, 10));
		}
		lines.add(blank());

		lines.add(header("▣ On record (" + base.containers.size() + ")", GOLD));
		if (base.containers.isEmpty()) {
			lines.add(indented("Open the chests, barrels and shulker boxes in your base once to put their contents on record.", GRAY, 10));
		}
		base.containers.entrySet().stream()
				.sorted(Comparator.comparingLong(e -> -e.getValue().updated))
				.forEach(e -> {
					Base.ContainerSnapshot snapshot = e.getValue();
					BlockPos pos = BlockPos.fromLong(e.getKey());
					ItemStack icon = BlockStates.icon(BlockStates.parse(snapshot.block));
					int total = snapshot.items.values().stream().mapToInt(Integer::intValue).sum();
					lines.add(new Line(Text.literal(BlockStates.name(snapshot.block).getString() + " at " + Alerts.pos(pos)
							+ " · " + total + " items, checked " + Alerts.ago(snapshot.updated)), icon, WHITE, 8, false,
							List.of(new Action("Show me", CYAN, () -> findPos(pos, BlockStates.name(snapshot.block).getString(), icon), TIP_SHOW))));
				});
		return lines;
	}

	private List<Line> rebuild() {
		List<Line> lines = new ArrayList<>();
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return lines;
		List<Materials.Need> needs = Materials.compute(base, BaseInsuranceClient.INSTANCE.manager, mc);
		int added = base.count(Base.Kind.ADDED);
		if (needs.isEmpty() && added == 0) {
			lines.add(text("✔ Nothing to rebuild.", GREEN));
			return lines;
		}
		int blocks = needs.stream().mapToInt(Materials.Need::needed).sum();
		List<Action> headerActions = new ArrayList<>();
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("hoardeye") && !needs.isEmpty()) {
			headerActions.add(new Action("Fetch with Hoard Eye", CYAN, () -> {
				flash(sendToFetchList(needs) ? "Sent to Hoard Eye: open Storage Search (Y) → Fetch list" : "Couldn't write the fetch list");
			}));
		}
		lines.add(new Line(Text.literal("To restore " + base.name + ": " + blocks + " blocks of " + needs.size() + " kinds"),
				ItemStack.EMPTY, GOLD, 0, true, headerActions));
		lines.add(text("Green: you're carrying enough. Yellow: enough in your chests. Red: you'll need more. Show me marks the chest it's in.", GRAY));
		if (added > 0) lines.add(text(Alerts.plural(added, "block") + " placed by someone else just need removing (orange in the overlay).", 0xFF000000 | Overlay.ADDED));
		lines.add(blank());
		for (Materials.Need need : needs) {
			int color = need.carried() >= need.needed() ? GREEN : need.carried() + need.stored() >= need.needed() ? YELLOW : RED;
			lines.add(new Line(Text.literal(need.item().getName().getString() + ": need " + need.needed() + " · carrying "
					+ need.carried() + " · in storage " + need.stored()), new ItemStack(need.item()), color, 0, false, List.of()));
			for (Materials.Source source : need.sources().subList(0, Math.min(3, need.sources().size()))) {
				ItemStack icon = new ItemStack(need.item());
				lines.add(new Line(Text.literal("↳ " + source.label() + " at " + Alerts.pos(source.pos()) + " (" + (int) source.distance() + "m): "
						+ source.count()), ItemStack.EMPTY, GRAY, 28, false,
						List.of(new Action("Show me", CYAN, () -> findPos(source.pos(), need.item().getName().getString(), icon), "Marks the chest it's in"))));
			}
		}
		return lines;
	}

	private List<Line> timeline() {
		List<Line> lines = new ArrayList<>();
		if (base.events.isEmpty()) lines.add(text("Nothing has happened yet.", GRAY));
		for (int i = base.events.size() - 1; i >= 0; i--) {
			Base.Event event = base.events.get(i);
			int color = switch (event.type) {
				case AWAY_DAMAGE, LIVE_DAMAGE, CONTAINER -> RED;
				case PLAYER -> PURPLE;
				case ALL_CLEAR -> GREEN;
				case CREATED, ACCEPTED -> GRAY;
			};
			lines.add(text("[" + Alerts.time(event.time) + "] " + event.text, color));
		}
		return lines;
	}

	// ---- Helpers ----

	/**
	 * Hands the materials still needed (beyond what you're carrying) to Hoard Eye's fetch list, through a
	 * small file it picks up next time Storage Search opens.
	 */
	private static boolean sendToFetchList(List<Materials.Need> needs) {
		com.google.gson.JsonArray items = new com.google.gson.JsonArray();
		for (Materials.Need need : needs) {
			int missing = need.needed() - need.carried();
			if (missing <= 0) continue;
			com.google.gson.JsonObject item = new com.google.gson.JsonObject();
			item.addProperty("id", Registries.ITEM.getId(need.item()).toString());
			item.addProperty("count", missing);
			items.add(item);
		}
		com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.addProperty("from", "Base Insurance");
		root.add("items", items);
		try {
			java.nio.file.Files.writeString(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("hoardeye-fetch.json"),
					root.toString());
			return true;
		} catch (java.io.IOException e) {
			BaseInsuranceClient.LOGGER.warn("Could not write fetch list", e);
			return false;
		}
	}

	private static ItemStack entityIcon(Base.EntityRecord record) {
		return switch (record.type) {
			case "minecraft:glow_item_frame" -> new ItemStack(net.minecraft.item.Items.GLOW_ITEM_FRAME);
			case "minecraft:armor_stand" -> new ItemStack(net.minecraft.item.Items.ARMOR_STAND);
			case "minecraft:painting" -> new ItemStack(net.minecraft.item.Items.PAINTING);
			default -> new ItemStack(net.minecraft.item.Items.ITEM_FRAME);
		};
	}

	/** Containers on record whose block has since been removed or replaced. */
	private List<Long> destroyedContainers() {
		List<Long> result = new ArrayList<>();
		for (long key : base.containers.keySet()) {
			Base.Change change = base.changes.get(key);
			if (change != null && change.kind() != Base.Kind.ADDED) result.add(key);
		}
		return result;
	}

	private static void addItems(List<Line> lines, Map<String, Integer> items, int limit, int indent) {
		items.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.limit(limit)
				.forEach(e -> {
					Identifier id = Identifier.tryParse(e.getKey());
					ItemStack icon = id != null && Registries.ITEM.containsId(id) ? new ItemStack(Registries.ITEM.get(id)) : ItemStack.EMPTY;
					lines.add(new Line(Text.literal(ContainerWatcher.itemName(e.getKey()) + " ×" + e.getValue()), icon, WHITE, indent, false, List.of()));
				});
		if (items.size() > limit) lines.add(new Line(Text.literal("+" + (items.size() - limit) + " more kinds"), ItemStack.EMPTY, GRAY, indent, false, List.of()));
	}

	private static Line header(String text, int color) {
		return new Line(Text.literal(text), ItemStack.EMPTY, color, 0, true, List.of());
	}

	private static Line text(String text, int color) {
		return new Line(Text.literal(text), ItemStack.EMPTY, color, 0, false, List.of());
	}

	private static Line line(String text, int color, List<Action> actions) {
		return new Line(Text.literal(text), ItemStack.EMPTY, color, 0, false, actions);
	}

	private static Line indented(String text, int color, int indent) {
		return new Line(Text.literal(text), ItemStack.EMPTY, color, indent, false, List.of());
	}

	private static Line blank() {
		return new Line(Text.empty(), ItemStack.EMPTY, WHITE, 0, false, List.of());
	}

	// ---- Drawing and input ----

	private int listBottom() {
		return height - 32;
	}

	private double maxScroll() {
		return Math.max(0, contentHeight - (listBottom() - TOP - 4));
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		super.renderBackground(context, mouseX, mouseY, delta);
		hits.clear();

		// Header: title, and status badges.
		context.drawTextWithShadow(textRenderer, Text.literal("⚑ ").styled(s -> s.withColor(CYAN)).append(title), PAD, 10, GOLD);
		int percent = base.snapshotPercent();
		int badgeX = badge(context, width - PAD, percent == 100 ? "Fully checked" : "Checked " + percent + "%", percent == 100 ? GREEN : YELLOW);
		badge(context, badgeX - 4, base.damageCount() == 0 ? "✔ All good" : "⚠ " + base.damageCount() + " changed", base.damageCount() == 0 ? GREEN : RED);

		// Content panel.
		int bottom = listBottom();
		context.fill(PAD, TOP - 4, width - PAD, bottom, PANEL);
		context.drawStrokedRectangle(PAD, TOP - 4, width - 2 * PAD, bottom - TOP + 4, BORDER);
		context.enableScissor(PAD + 1, TOP - 3, width - PAD - 1, bottom - 1);
		int y = TOP - (int) scroll;
		int left = 2 * PAD, right = width - 2 * PAD;
		for (Row row : rows) {
			if (y + row.height >= TOP - 4 && y <= bottom) {
				boolean hovered = !row.actions.isEmpty() && mouseX >= PAD && mouseX < width - PAD && mouseY >= y && mouseY < y + row.height
						&& mouseY >= TOP - 4 && mouseY < bottom;
				if (hovered) context.fill(PAD + 1, y, width - PAD - 1, y + row.height, 0x20FFFFFF);
				int x = left + row.indent;
				int textY = row.height == ICON_HEIGHT || row.header ? y + 6 : y + 1;
				if (!row.icon.isEmpty()) {
					context.drawItem(row.icon, x, y + 2);
					x += 20;
				}
				context.drawText(textRenderer, row.text, x, textY, row.color, true);
				if (row.bar >= 0) {
					int bx0 = x, bx1 = Math.min(right - 4, x + 200), by = y + row.height - 7;
					context.fill(bx0, by, bx1, by + 4, 0x40FFFFFF);
					context.fill(bx0, by, bx0 + (int) ((bx1 - bx0) * Math.min(1, row.bar)), by + 4, row.color);
				}
				if (row.header) context.fill(left, y + 17, right, y + 18, (row.color & 0xFFFFFF) | 0x50000000);
				// Action chips, right-aligned.
				int ax = right;
				for (int i = row.actions.size() - 1; i >= 0; i--) {
					Action action = row.actions.get(i);
					int w = textRenderer.getWidth(action.label) + 8;
					int ay = y + (row.height - 14) / 2;
					boolean over = mouseX >= ax - w && mouseX < ax && mouseY >= ay && mouseY < ay + 14 && mouseY >= TOP - 4 && mouseY < bottom;
					context.fill(ax - w, ay, ax, ay + 14, (action.color & 0xFFFFFF) | (over ? 0x70000000 : 0x30000000));
					context.drawStrokedRectangle(ax - w, ay, w, 14, action.color);
					context.drawTextWithShadow(textRenderer, action.label, ax - w + 4, ay + 3, over ? 0xFFFFFFFF : action.color);
					if (ay + 14 > TOP - 4 && ay < bottom) hits.add(new Hit(ax - w, Math.max(ay, TOP - 4), ax, Math.min(ay + 14, bottom), action.run, action.tip));
					ax -= w + 4;
				}
			}
			y += row.height;
		}
		context.disableScissor();

		double max = maxScroll();
		if (max > 0) {
			int view = bottom - TOP;
			int bar = Math.max(20, (int) (view * view / (view + max)));
			int barY = TOP + (int) ((view - bar) * scroll / max);
			context.fill(width - PAD - 4, TOP - 3, width - PAD - 2, bottom - 1, 0x30FFFFFF);
			context.fill(width - PAD - 4, barY, width - PAD - 2, barY + bar, CYAN);
		}

		if (status != null && System.currentTimeMillis() < statusUntil) {
			int w = textRenderer.getWidth(status);
			context.fill(width / 2 - w / 2 - 6, bottom - 18, width / 2 + w / 2 + 6, bottom - 4, 0xE0102018);
			context.drawStrokedRectangle(width / 2 - w / 2 - 6, bottom - 18, w + 12, 14, GREEN);
			context.drawTextWithShadow(textRenderer, status, width / 2 - w / 2, bottom - 15, GREEN);
		}
	}

	private int badge(DrawContext context, int rightX, String text, int color) {
		int w = textRenderer.getWidth(text) + 10;
		context.fill(rightX - w, 6, rightX, 20, (color & 0xFFFFFF) | 0x30000000);
		context.drawStrokedRectangle(rightX - w, 6, w, 14, color);
		context.drawTextWithShadow(textRenderer, text, rightX - w + 5, 9, color);
		return rightX - w;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		for (Hit hit : hits) {
			if (hit.tip != null && mouseX >= hit.x0 && mouseX < hit.x1 && mouseY >= hit.y0 && mouseY < hit.y1) {
				context.drawOrderedTooltip(textRenderer, textRenderer.wrapLines(Text.literal(hit.tip), 220), mouseX, mouseY);
			}
		}
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (super.mouseClicked(click, doubled)) return true;
		for (Hit hit : List.copyOf(hits)) {
			if (click.x() >= hit.x0 && click.x() < hit.x1 && click.y() >= hit.y0 && click.y() < hit.y1) {
				hit.run.run();
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll = Math.max(0, Math.min(maxScroll(), scroll - verticalAmount * 24));
		return true;
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	/** Every row's text and actions, for the self-test log. */
	public String testDescribe() {
		StringBuilder sb = new StringBuilder();
		for (Row row : rows) {
			StringBuilder text = new StringBuilder();
			row.text.accept((index, style, codePoint) -> {
				text.appendCodePoint(codePoint);
				return true;
			});
			sb.append(text).append(row.actions.isEmpty() ? "" : " " + row.actions.stream().map(Action::label).toList()).append(" | ");
		}
		return sb.toString();
	}

	/** Runs the first row action with this label; used by the self-test. */
	public boolean testAction(String label) {
		for (Row row : rows) {
			for (Action action : row.actions) {
				if (action.label.equals(label)) {
					action.run.run();
					return true;
				}
			}
		}
		return false;
	}
}
