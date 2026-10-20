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
 * "not damage" (ignore that block type), all undoable.
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

	/** A clickable chip at the right of a row. */
	private record Action(String label, int color, Runnable run) {}

	private record Line(Text text, ItemStack icon, int color, int indent, boolean header, List<Action> actions) {}

	private record Row(OrderedText text, ItemStack icon, int color, int indent, boolean header, List<Action> actions, int height) {}

	private record Hit(int x0, int y0, int x1, int y1, Runnable run) {}

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
					if (confirmed) flash("Accepted " + BaseActions.acceptAll(base) + " changes · Undo is available");
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
				rows.add(new Row(wrapped.get(i), first ? line.icon : ItemStack.EMPTY, line.color,
						line.indent + (first ? 0 : iconSpace), line.header && first, first ? line.actions : List.of(), height));
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
		flash("Accepted " + BaseActions.accept(base, keys, "Accepted " + what) + " · Undo is available");
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
		flash(blockName + " no longer counts as damage (" + accepted + " cleared) · Undo is available");
		clearAndInit();
	}

	private static boolean involves(BlockState state, String blockId) {
		return state != null && Registries.BLOCK.getId(state.getBlock()).toString().equals(blockId);
	}

	// ---- Tabs ----

	private List<Line> overview() {
		List<Line> lines = new ArrayList<>();
		lines.add(header(base.name + " — " + base.dimension.replace("minecraft:", "") + ", " + (base.maxX - base.minX + 1) + "×"
				+ (base.maxY - base.minY + 1) + "×" + (base.maxZ - base.minZ + 1) + " blocks", GOLD));
		lines.add(line("From " + base.minX + " " + base.minY + " " + base.minZ + " to " + base.maxX + " " + base.maxY + " " + base.maxZ
				+ ". Insured since " + Alerts.time(base.created) + ". Last visit " + Alerts.ago(base.lastSeen) + ".", GRAY,
				List.of(new Action("Map", CYAN, () -> client.setScreen(MapScreen.centeredOn(base))))));
		lines.add(blank());

		if (base.damageCount() == 0) {
			lines.add(text("✔ Everything matches the snapshot.", GREEN));
		} else if (base.changes.isEmpty()) {
			lines.add(line("⚠ " + Alerts.plural(base.entityChanges.size(), "item frame, painting or armor stand") + " changed", RED,
					List.of(new Action("See damage", RED, () -> showTab("DAMAGE")))));
		} else {
			int live = 0;
			for (Base.Change change : base.changes.values()) if (change.live) live++;
			lines.add(line("⚠ " + Alerts.plural(base.changes.size(), "block") + " differ from the snapshot", RED,
					List.of(new Action("See damage", RED, () -> showTab("DAMAGE")))));
			lines.add(indented("■ " + base.count(Base.Kind.REMOVED) + " removed", 0xFF000000 | Overlay.REMOVED, 10));
			lines.add(indented("■ " + base.count(Base.Kind.ADDED) + " placed by someone else", 0xFF000000 | Overlay.ADDED, 10));
			lines.add(indented("■ " + base.count(Base.Kind.REPLACED) + " replaced", 0xFF000000 | Overlay.REPLACED, 10));
			lines.add(indented((base.changes.size() - live) + " found when you came back, " + live + " happened while you were here.", GRAY, 10));
			if (!base.entityChanges.isEmpty()) {
				lines.add(indented("■ " + Alerts.plural(base.entityChanges.size(), "item frame, painting or armor stand") + " changed",
						0xFF000000 | Overlay.ENTITY, 10));
			}
		}
		lines.add(blank());

		int percent = base.snapshotPercent();
		if (percent < 100) {
			lines.add(text("◔ Snapshot " + percent + "% seen up close", YELLOW));
			lines.add(indented("Only blocks within " + BIConfig.INSTANCE.trustRadius + " blocks of you are trusted, because servers fake blocks "
					+ "further away. Walk around the whole base once; parts you haven't been near can't be checked yet.", GRAY, 10));
		} else {
			lines.add(text("● Snapshot complete: every block has been seen up close.", GREEN));
		}
		lines.add(blank());

		int destroyed = destroyedContainers().size();
		long thefts = base.events.stream().filter(e -> e.type == Base.EventType.CONTAINER).count();
		lines.add(line("▣ " + base.containers.size() + " containers on record"
				+ (destroyed > 0 ? ", " + destroyed + " destroyed" : "")
				+ (thefts > 0 ? ", " + Alerts.plural((int) thefts, "theft report") : "") + ".", destroyed + thefts > 0 ? RED : WHITE,
				destroyed + thefts > 0 ? List.of(new Action("Details", RED, () -> showTab("CONTAINERS"))) : List.of()));
		lines.add(blank());

		List<Base.Event> sightings = base.events.stream().filter(e -> e.type == Base.EventType.PLAYER).toList();
		lines.add(text("◉ Players seen near this base: " + (sightings.isEmpty() ? "none" : sightings.size()), sightings.isEmpty() ? GREEN : PURPLE));
		for (int i = sightings.size() - 1; i >= Math.max(0, sightings.size() - 6); i--) {
			lines.add(indented("[" + Alerts.time(sightings.get(i).time) + "] " + sightings.get(i).text, GRAY, 10));
		}
		return lines;
	}

	/** Changes grouped by kind and block, each group with Find / Accept / Not damage. */
	private List<Line> damage() {
		List<Line> lines = new ArrayList<>();
		if (base.damageCount() == 0) {
			lines.add(text("✔ Nothing has changed.", GREEN));
			return lines;
		}
		lines.add(text("Find walks you there with markers that show through walls. Accept makes it the new normal. "
				+ "Not damage also ignores that block type from now on. All of these can be undone.", GRAY));
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
				case REMOVED -> "Removed";
				case ADDED -> "Placed by someone else";
				case REPLACED -> "Replaced";
			};
			List<Long> allOfKind = new ArrayList<>();
			groups.values().forEach(allOfKind::addAll);
			lines.add(new Line(Text.literal(title + " (" + total + ")"), ItemStack.EMPTY, 0xFF000000 | Overlay.color(kind), 0, true,
					List.of(new Action("Find all", CYAN, () -> find(allOfKind, title, ItemStack.EMPTY)),
							new Action("Accept all", ACCEPT, () -> acceptGroup(allOfKind, "all " + title.toLowerCase())))));
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
						actions.add(new Action("Find", CYAN, () -> find(keys, name, icon)));
						actions.add(new Action("Accept", ACCEPT, () -> acceptGroup(keys, name)));
						// "Not damage" ignores the block that's there now (or, for removals, the one that went missing).
						if (shown != null && !shown.isAir()) {
							String blockId = Registries.BLOCK.getId(shown.getBlock()).toString();
							actions.add(new Action("Not damage", NOT_DAMAGE, () -> notDamage(blockId)));
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
					List.of(new Action("Accept all", ACCEPT, () -> {
						flash("Accepted " + BaseActions.acceptEntities(base, allEntities, "Accepted all entity changes") + " · Undo is available");
						clearAndInit();
					}))));
			base.entityChanges.entrySet().stream()
					.sorted(Comparator.comparingLong(e -> -e.getValue().firstSeen))
					.forEach(e -> {
						String key = e.getKey();
						Base.EntityChange change = e.getValue();
						ItemStack icon = entityIcon(change.was);
						String text = change.describe() + " at " + Alerts.pos(change.was.pos()) + " · "
								+ (change.live ? "live" : "found on return") + ", " + Alerts.ago(change.firstSeen);
						lines.add(new Line(Text.literal(text), icon, WHITE, 8, false, List.of(
								new Action("Find", CYAN, () -> findPos(change.was.pos(), change.was.name, icon)),
								new Action("Accept", ACCEPT, () -> {
									flash("Accepted " + BaseActions.acceptEntities(base, List.of(key), "Accepted " + change.describe()) + " · Undo is available");
									clearAndInit();
								}))));
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
							case REMOVED -> BlockStates.name(change.was).getString() + " missing";
							case ADDED -> BlockStates.name(change.now).getString() + " placed";
							case REPLACED -> BlockStates.name(change.was).getString() + " is now " + BlockStates.name(change.now).getString();
						};
						ItemStack icon = BlockStates.icon(change.kind() == Base.Kind.ADDED ? change.nowState() : change.wasState());
						lines.add(new Line(Text.literal(what + " at " + Alerts.pos(pos) + " (" + (int) Math.sqrt(pos.getSquaredDistance(me)) + "m) · "
								+ (change.live ? "live" : "found on return") + ", " + Alerts.ago(change.firstSeen)), icon,
								0xFF000000 | Overlay.color(change.kind()), 8, false,
								List.of(new Action("Find", CYAN, () -> findPos(pos, what, icon)),
										new Action("Accept", ACCEPT, () -> acceptGroup(List.of(e.getKey()), what)))));
					});
		}
		return lines;
	}

	private List<Line> containers() {
		List<Line> lines = new ArrayList<>();
		List<Long> destroyed = destroyedContainers();
		if (!destroyed.isEmpty()) {
			lines.add(header("✖ Destroyed containers (" + destroyed.size() + ")", RED));
			for (long key : destroyed) {
				Base.ContainerSnapshot snapshot = base.containers.get(key);
				BlockPos pos = BlockPos.fromLong(key);
				ItemStack icon = BlockStates.icon(BlockStates.parse(snapshot.block));
				lines.add(new Line(Text.literal(BlockStates.name(snapshot.block).getString() + " at " + Alerts.pos(pos)
						+ " · last contents (" + Alerts.time(snapshot.updated) + "):"), icon, WHITE, 8, false,
						List.of(new Action("Find", CYAN, () -> findPos(pos, "Destroyed container", icon)))));
				addItems(lines, snapshot.items, 8, 28);
			}
			lines.add(blank());
		}

		List<Base.Event> thefts = base.events.stream().filter(e -> e.type == Base.EventType.CONTAINER).toList();
		lines.add(header("⚠ Theft reports (" + thefts.size() + ")", thefts.isEmpty() ? GREEN : RED));
		if (thefts.isEmpty()) lines.add(indented("Nothing missing from any container you've opened.", GRAY, 10));
		for (int i = thefts.size() - 1; i >= 0; i--) {
			lines.add(indented("[" + Alerts.time(thefts.get(i).time) + "] " + thefts.get(i).text, YELLOW, 10));
		}
		lines.add(blank());

		lines.add(header("▣ Containers on record (" + base.containers.size() + ")", GOLD));
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
							List.of(new Action("Find", CYAN, () -> findPos(pos, BlockStates.name(snapshot.block).getString(), icon)))));
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
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("containerpeek") && !needs.isEmpty()) {
			headerActions.add(new Action("Fetch with Container Peek", CYAN, () -> {
				flash(sendToFetchList(needs) ? "Sent to Container Peek: open Storage Search (Y) → Fetch list" : "Couldn't write the fetch list");
			}));
		}
		lines.add(new Line(Text.literal("To restore " + base.name + ": " + blocks + " blocks of " + needs.size() + " kinds"),
				ItemStack.EMPTY, GOLD, 0, true, headerActions));
		lines.add(text("Green: you're carrying enough. Yellow: enough in storage. Red: you'll need more. Find walks you to the storage.", GRAY));
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
						List.of(new Action("Find", CYAN, () -> findPos(source.pos(), need.item().getName().getString(), icon)))));
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
	 * Hands the materials still needed (beyond what you're carrying) to Container Peek's fetch list, through a
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
			java.nio.file.Files.writeString(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("containerpeek-fetch.json"),
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
		int badgeX = badge(context, width - PAD, "Snapshot " + percent + "%", percent == 100 ? GREEN : YELLOW);
		badge(context, badgeX - 4, base.damageCount() == 0 ? "Intact" : "Damaged · " + base.damageCount(), base.damageCount() == 0 ? GREEN : RED);

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
					if (ay + 14 > TOP - 4 && ay < bottom) hits.add(new Hit(ax - w, Math.max(ay, TOP - 4), ax, Math.min(ay + 14, bottom), action.run));
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
