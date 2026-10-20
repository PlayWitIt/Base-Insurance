package com.greysworkshop.baseinsurance;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The damage report: overview, changed blocks, containers, rebuild materials and history. */
public class ReportScreen extends Screen {
	private enum Tab { OVERVIEW, BLOCKS, CONTAINERS, REBUILD, TIMELINE }

	private static final int TOP = 54;
	private static final int SIDE = 20;
	private static final int TEXT_HEIGHT = 11;
	private static final int ICON_HEIGHT = 18;

	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFAAAAAA;
	private static final int GOLD = 0xFFFFAA00;
	private static final int GREEN = 0xFF55FF55;
	private static final int RED = 0xFFFF5555;
	private static final int YELLOW = 0xFFFFFF55;
	private static final int PURPLE = 0xFFFF77FF;

	private record Line(Text text, ItemStack icon, int color, int indent) {}

	private record Row(OrderedText text, ItemStack icon, int color, int indent, int height) {}

	private final Base base;
	private Tab tab = Tab.OVERVIEW;
	private final List<Row> rows = new ArrayList<>();
	private int contentHeight;
	private double scroll;

	public ReportScreen(Base base) {
		super(Text.translatable("baseinsurance.report.title", base.name));
		this.base = base;
	}

	@Override
	protected void init() {
		Tab[] tabs = Tab.values();
		int tabWidth = Math.min(80, (width - 2 * SIDE) / tabs.length - 4);
		int x = width / 2 - (tabs.length * (tabWidth + 4) - 4) / 2;
		for (Tab t : tabs) {
			Text label = Text.translatable("baseinsurance.report.tab." + t.name().toLowerCase());
			ButtonWidget button = ButtonWidget.builder(label, b -> {
				tab = t;
				scroll = 0;
				clearAndInit();
			}).dimensions(x, 26, tabWidth, 20).build();
			button.active = t != tab;
			addDrawableChild(button);
			x += tabWidth + 4;
		}

		int y = height - 26;
		addDrawableChild(ButtonWidget.builder(overlayLabel(), b -> {
			Overlay.enabled = !Overlay.enabled;
			b.setMessage(overlayLabel());
		}).dimensions(width / 2 - 154, y, 100, 20).build());
		ButtonWidget accept = ButtonWidget.builder(Text.translatable("baseinsurance.report.accept"), b ->
				client.setScreen(new ConfirmScreen(confirmed -> {
					if (confirmed) Commands.acceptChanges(base, null, -1);
					client.setScreen(this);
				}, Text.translatable("baseinsurance.report.accept.title"),
						Text.translatable("baseinsurance.report.accept.message", base.changes.size(), base.name))))
				.dimensions(width / 2 - 50, y, 100, 20).build();
		accept.active = !base.changes.isEmpty();
		addDrawableChild(accept);
		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
				.dimensions(width / 2 + 54, y, 100, 20).build());

		rebuildRows();
	}

	/** Switches tab by name, e.g. "BLOCKS". */
	public void showTab(String name) {
		tab = Tab.valueOf(name);
		scroll = 0;
		clearAndInit();
	}

	private static Text overlayLabel() {
		return ScreenTexts.composeToggleText(Text.translatable("baseinsurance.report.overlay"), Overlay.enabled);
	}

	private void rebuildRows() {
		List<Line> lines = switch (tab) {
			case OVERVIEW -> overview();
			case BLOCKS -> blocks();
			case CONTAINERS -> containers();
			case REBUILD -> rebuild();
			case TIMELINE -> timeline();
		};
		rows.clear();
		contentHeight = 0;
		int textWidth = width - 2 * SIDE;
		for (Line line : lines) {
			int iconSpace = line.icon.isEmpty() ? 0 : 20;
			List<OrderedText> wrapped = textRenderer.wrapLines(line.text, textWidth - line.indent - iconSpace);
			if (wrapped.isEmpty()) wrapped = List.of(OrderedText.EMPTY);
			for (int i = 0; i < wrapped.size(); i++) {
				boolean first = i == 0;
				int height = first && !line.icon.isEmpty() ? ICON_HEIGHT : TEXT_HEIGHT;
				rows.add(new Row(wrapped.get(i), first ? line.icon : ItemStack.EMPTY, line.color, line.indent + (first ? 0 : iconSpace), height));
				contentHeight += height;
			}
		}
	}

	// ---- Tabs ----

	private List<Line> overview() {
		List<Line> lines = new ArrayList<>();
		lines.add(text(base.name + " — " + base.dimension.replace("minecraft:", "") + ", " + (base.maxX - base.minX + 1) + "×"
				+ (base.maxY - base.minY + 1) + "×" + (base.maxZ - base.minZ + 1) + " blocks", GOLD));
		lines.add(text("From " + base.minX + " " + base.minY + " " + base.minZ + " to " + base.maxX + " " + base.maxY + " " + base.maxZ
				+ ". Insured since " + Alerts.time(base.created) + ". Last visit " + Alerts.ago(base.lastSeen) + ".", GRAY));
		lines.add(blank());

		if (base.changes.isEmpty()) {
			lines.add(text("✔ Everything matches the snapshot.", GREEN));
		} else {
			int live = 0;
			for (Base.Change change : base.changes.values()) if (change.live) live++;
			lines.add(text("⚠ " + base.changes.size() + " blocks differ from the snapshot", RED));
			lines.add(indented("■ " + base.count(Base.Kind.REMOVED) + " removed", 0xFF000000 | Overlay.REMOVED, 10));
			lines.add(indented("■ " + base.count(Base.Kind.ADDED) + " placed by someone else", 0xFF000000 | Overlay.ADDED, 10));
			lines.add(indented("■ " + base.count(Base.Kind.REPLACED) + " replaced", 0xFF000000 | Overlay.REPLACED, 10));
			lines.add(indented((base.changes.size() - live) + " found when you came back, " + live + " happened while you were here.", GRAY, 10));
		}
		lines.add(blank());

		int destroyed = destroyedContainers().size();
		long thefts = base.events.stream().filter(e -> e.type == Base.EventType.CONTAINER).count();
		lines.add(text("▣ " + base.containers.size() + " containers on record"
				+ (destroyed > 0 ? ", " + destroyed + " destroyed" : "")
				+ (thefts > 0 ? ", " + thefts + " theft report(s)" : "") + ".", destroyed + thefts > 0 ? RED : WHITE));
		int percent = base.snapshotPercent();
		if (percent < 100) {
			lines.add(indented("Snapshot " + percent + "% seen up close. Walk around the base (within "
					+ BIConfig.INSTANCE.trustRadius + " blocks of every part) to finish it; unseen parts can't be checked yet.", YELLOW, 10));
		} else {
			lines.add(indented("Snapshot complete: every block has been seen up close.", GREEN, 10));
		}
		lines.add(blank());

		List<Base.Event> sightings = base.events.stream().filter(e -> e.type == Base.EventType.PLAYER).toList();
		lines.add(text("◉ Players seen near this base: " + (sightings.isEmpty() ? "none" : ""), sightings.isEmpty() ? GREEN : PURPLE));
		for (int i = sightings.size() - 1; i >= Math.max(0, sightings.size() - 6); i--) {
			lines.add(indented("[" + Alerts.time(sightings.get(i).time) + "] " + sightings.get(i).text, GRAY, 10));
		}
		lines.add(blank());
		lines.add(text("Tip: turn on the overlay and walk around to see every change. Aim at one to see what belongs there. "
				+ "Repaired blocks clear themselves. If a change was yours, use Accept all, or /bi accept <name> <radius> for just one area.", GRAY));
		return lines;
	}

	private List<Line> blocks() {
		List<Line> lines = new ArrayList<>();
		if (base.changes.isEmpty()) {
			lines.add(text("No changed blocks.", GREEN));
			return lines;
		}
		for (Base.Kind kind : Base.Kind.values()) {
			Map<String, Integer> counts = new LinkedHashMap<>();
			Map<String, BlockState> icons = new LinkedHashMap<>();
			for (Base.Change change : base.changes.values()) {
				if (change.kind() != kind) continue;
				String key = switch (kind) {
					case REMOVED -> BlockStates.name(change.was).getString();
					case ADDED -> BlockStates.name(change.now).getString();
					case REPLACED -> BlockStates.name(change.was).getString() + " → " + BlockStates.name(change.now).getString();
				};
				counts.merge(key, 1, Integer::sum);
				icons.putIfAbsent(key, kind == Base.Kind.ADDED ? change.nowState() : change.wasState());
			}
			if (counts.isEmpty()) continue;
			String header = switch (kind) {
				case REMOVED -> "Removed";
				case ADDED -> "Placed by someone else";
				case REPLACED -> "Replaced";
			};
			int color = 0xFF000000 | Overlay.color(kind);
			lines.add(text(header + " (" + counts.values().stream().mapToInt(Integer::intValue).sum() + ")", color));
			counts.entrySet().stream()
					.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
					.forEach(e -> lines.add(new Line(Text.literal(e.getKey() + " ×" + e.getValue()), BlockStates.icon(icons.get(e.getKey())), WHITE, 10)));
			lines.add(blank());
		}

		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null && base.dimension.equals(BaseManager.dimensionKey(mc.world))) {
			lines.add(text("Nearest changes to you", GOLD));
			BlockPos me = mc.player.getBlockPos();
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
						lines.add(indented(what + " at " + Alerts.pos(pos) + " (" + (int) Math.sqrt(pos.getSquaredDistance(me)) + "m) — "
								+ (change.live ? "live" : "found on return") + ", " + Alerts.ago(change.firstSeen), 0xFF000000 | Overlay.color(change.kind()), 10));
					});
		}
		return lines;
	}

	private List<Line> containers() {
		List<Line> lines = new ArrayList<>();
		List<Long> destroyed = destroyedContainers();
		if (!destroyed.isEmpty()) {
			lines.add(text("✖ Destroyed containers (" + destroyed.size() + ")", RED));
			for (long key : destroyed) {
				Base.ContainerSnapshot snapshot = base.containers.get(key);
				lines.add(indented(BlockStates.name(snapshot.block).getString() + " at " + Alerts.pos(BlockPos.fromLong(key))
						+ " — last contents (" + Alerts.time(snapshot.updated) + "):", WHITE, 10));
				addItems(lines, snapshot.items, 8, 20);
			}
			lines.add(blank());
		}

		List<Base.Event> thefts = base.events.stream().filter(e -> e.type == Base.EventType.CONTAINER).toList();
		lines.add(text("⚠ Theft reports (" + thefts.size() + ")", thefts.isEmpty() ? GREEN : RED));
		if (thefts.isEmpty()) lines.add(indented("Nothing missing from any container you've opened.", GRAY, 10));
		for (int i = thefts.size() - 1; i >= 0; i--) {
			lines.add(indented("[" + Alerts.time(thefts.get(i).time) + "] " + thefts.get(i).text, YELLOW, 10));
		}
		lines.add(blank());

		lines.add(text("▣ Containers on record (" + base.containers.size() + ")", GOLD));
		if (base.containers.isEmpty()) {
			lines.add(indented("Open the chests, barrels and shulker boxes in your base once to put their contents on record.", GRAY, 10));
		}
		base.containers.entrySet().stream()
				.sorted(Comparator.comparingLong(e -> -e.getValue().updated))
				.forEach(e -> {
					Base.ContainerSnapshot snapshot = e.getValue();
					int total = snapshot.items.values().stream().mapToInt(Integer::intValue).sum();
					lines.add(indented(BlockStates.name(snapshot.block).getString() + " at " + Alerts.pos(BlockPos.fromLong(e.getKey()))
							+ " — " + total + " items, checked " + Alerts.ago(snapshot.updated), WHITE, 10));
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
			lines.add(text("Nothing to rebuild.", GREEN));
			return lines;
		}
		int blocks = needs.stream().mapToInt(Materials.Need::needed).sum();
		lines.add(text("To restore " + base.name + ": " + blocks + " blocks of " + needs.size() + " kinds.", GOLD));
		lines.add(text("Green: you're carrying enough. Yellow: you have enough in storage. Red: you'll need more.", GRAY));
		if (added > 0) lines.add(text(added + " block(s) placed by someone else just need removing (orange in the overlay).", 0xFF000000 | Overlay.ADDED));
		lines.add(blank());
		for (Materials.Need need : needs) {
			int color = need.carried() >= need.needed() ? GREEN : need.carried() + need.stored() >= need.needed() ? YELLOW : RED;
			lines.add(new Line(Text.literal(need.item().getName().getString() + ": need " + need.needed() + " · carrying "
					+ need.carried() + " · in storage " + need.stored()), new ItemStack(need.item()), color, 0));
			for (Materials.Source source : need.sources().subList(0, Math.min(3, need.sources().size()))) {
				lines.add(indented("↳ " + source.label() + " at " + Alerts.pos(source.pos()) + " (" + (int) source.distance() + "m): "
						+ source.count(), GRAY, 20));
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
					lines.add(new Line(Text.literal(ContainerWatcher.itemName(e.getKey()) + " ×" + e.getValue()), icon, WHITE, indent));
				});
		if (items.size() > limit) lines.add(new Line(Text.literal("+" + (items.size() - limit) + " more kinds"), ItemStack.EMPTY, GRAY, indent));
	}

	private static Line text(String text, int color) {
		return new Line(Text.literal(text), ItemStack.EMPTY, color, 0);
	}

	private static Line indented(String text, int color, int indent) {
		return new Line(Text.literal(text), ItemStack.EMPTY, color, indent);
	}

	private static Line blank() {
		return new Line(Text.empty(), ItemStack.EMPTY, WHITE, 0);
	}

	// ---- Drawing and scrolling ----

	private int listBottom() {
		return height - 34;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title.copy().formatted(Formatting.BOLD), width / 2, 10, GOLD);

		int bottom = listBottom();
		context.enableScissor(0, TOP, width, bottom);
		int y = TOP - (int) scroll;
		for (Row row : rows) {
			if (y + row.height >= TOP && y <= bottom) {
				int x = SIDE + row.indent;
				if (!row.icon.isEmpty()) {
					context.drawItem(row.icon, x, y);
					context.drawText(textRenderer, row.text, x + 20, y + 4, row.color, true);
				} else {
					context.drawText(textRenderer, row.text, x, y + 1, row.color, true);
				}
			}
			y += row.height;
		}
		context.disableScissor();

		// Scrollbar.
		int viewHeight = bottom - TOP;
		if (contentHeight > viewHeight) {
			int barHeight = Math.max(20, viewHeight * viewHeight / contentHeight);
			int barY = TOP + (int) ((viewHeight - barHeight) * scroll / (contentHeight - viewHeight));
			context.fill(width - 8, TOP, width - 4, bottom, 0x40FFFFFF);
			context.fill(width - 8, barY, width - 4, barY + barHeight, 0xC0FFFFFF);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		int max = Math.max(0, contentHeight - (listBottom() - TOP));
		scroll = Math.max(0, Math.min(max, scroll - verticalAmount * 24));
		return true;
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
