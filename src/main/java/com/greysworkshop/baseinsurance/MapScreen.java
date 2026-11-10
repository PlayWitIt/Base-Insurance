package com.greysworkshop.baseinsurance;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2fStack;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * The explored-world map: your bases (green = intact, red = damaged), you, and in create mode a resizable
 * area for a new base.
 */
public class MapScreen extends Screen {
	private enum Drag { NONE, PAN, RULER, MOVE, N, S, E, W, NE, NW, SE, SW }
	/** What the text box at the top is for, when it's open. */
	private enum Field { NONE, GOTO, PIN }

	private static final int PANEL_WIDTH = 168;
	private static final int ACCENT = 0xFF40E0FF;
	private static final int GOLD = 0xFFFFC040;
	private static final int GRAY = 0xFF9090A8;
	private static final int WHITE = 0xFFF0F0F0;
	private static final int PANEL = 0xE0101018;
	private static final int BORDER = 0xFF33334D;
	/** Far enough out to see a world millions of blocks across on one screen. */
	static final double MIN_ZOOM = 1 / 4096.0;
	private static final double MAX_ZOOM = 16;
	private static final int MIN_SIZE = 4;

	// The view is remembered between openings (per session).
	private static double centerX = Double.NaN;
	private static double centerZ;
	private static double zoom = 2;
	private static boolean zoomLoaded;
	private static String viewDimension;
	/** Keep the view centred on you as you move, until you drag it somewhere else. */
	private static boolean following = true;

	private final boolean createMode;
	private final String initialName;
	/** The base being resized, or null when making a new one. */
	private final Base editing;
	/** Resizing: the bottom and top (absolute Y). */
	private int editMinY, editMaxY;

	// Create mode: the proposed area and height range.
	private int selMinX, selMaxX, selMinZ, selMaxZ;
	private int baseY;
	private int down = 24;
	private int up = 48;
	private TextFieldWidget nameField;
	private ButtonWidget confirmButton;
	/** The map's "Minimap: on/off" button (none while creating or resizing a base). */
	ButtonWidget minimapButton;
	/** The right-click menu: where it's drawn, and the spot (block x, z) it's for; null when it's closed. */
	int[] menu;
	/** Its rows as last drawn: x0, y0, x1, y1 and which (0 walk, 1 copy). */
	private final List<int[]> menuRows = new ArrayList<>();
	/** Why the last Walk here didn't go, shown in the menu. */
	private String menuNote;
	private String error;
	/** The box at the top: go to coordinates, or name a pin. */
	private TextFieldWidget topField;
	private Field fieldMode = Field.NONE;
	/** Where the pin being named goes. */
	private int pinX, pinZ;
	/** The spot Go to went to, marked until the map closes; null if none. */
	private static int[] gotoMark;
	/** The ruler, in blocks {fromX, fromZ, toX, toZ}; null when there's none. */
	private double[] ruler;
	/** The pin under the right-click, if any. */
	private MapPins.Pin menuPin;

	private Drag drag = Drag.NONE;
	private double pressX, pressY;
	private boolean moved;
	private int[] dragStart;
	/** Rows of the list in the corner: index, y, right edge, and 0 for a base or 1 for a pin. */
	private final List<int[]> baseListRows = new ArrayList<>();
	private final List<MapPins.Pin> listedPins = new ArrayList<>();

	public MapScreen() {
		this(false, null, null);
	}

	private MapScreen(boolean createMode, String name, Base editing) {
		super(editing != null ? Text.translatable("baseinsurance.map.edit_title", editing.name)
				: Text.translatable(createMode ? "baseinsurance.map.create_title" : "baseinsurance.map.title"));
		this.createMode = createMode;
		this.initialName = name;
		this.editing = editing;
		if (!zoomLoaded) {
			// The zoom you left the map at last game.
			zoom = MathHelper.clamp(BIConfig.INSTANCE.mapZoom, MIN_ZOOM, MAX_ZOOM);
			zoomLoaded = true;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) {
			BlockPos p = client.player.getBlockPos();
			String dimension = BaseManager.dimensionKey(client.world);
			if (createMode || Double.isNaN(centerX) || !dimension.equals(viewDimension)) {
				centerX = p.getX() + 0.5;
				centerZ = p.getZ() + 0.5;
				viewDimension = dimension;
			}
			following = !createMode;
			baseY = p.getY();
			selMinX = p.getX() - 48;
			selMaxX = p.getX() + 48;
			selMinZ = p.getZ() - 48;
			selMaxZ = p.getZ() + 48;
			if (createMode) zoom = Math.max(zoom, 1.5);
		}
		if (editing != null) {
			selMinX = editing.minX;
			selMaxX = editing.maxX;
			selMinZ = editing.minZ;
			selMaxZ = editing.maxZ;
			editMinY = editing.minY;
			editMaxY = editing.maxY;
			centerX = (editing.minX + editing.maxX + 1) / 2.0;
			centerZ = (editing.minZ + editing.maxZ + 1) / 2.0;
			following = false;
			fitZoom = true;
		}
	}

	/** Zoom to fit the base being resized on the first frame (the screen size isn't known before). */
	private boolean fitZoom;

	public static MapScreen forCreate(String name) {
		return new MapScreen(true, name, null);
	}

	/** Resizing an existing base: its area to drag about, and its bottom and top. */
	public static MapScreen forEdit(Base base) {
		return new MapScreen(true, base.name, base);
	}

	// ---- Coordinates ----

	private int mapRight() {
		return createMode ? width - PANEL_WIDTH - 8 : width;
	}

	private double mapCenterX() {
		return mapRight() / 2.0;
	}

	private double mapCenterY() {
		return height / 2.0;
	}

	private double screenX(double wx) {
		return mapCenterX() + (wx - centerX) * zoom;
	}

	private double screenY(double wz) {
		return mapCenterY() + (wz - centerZ) * zoom;
	}

	private double worldX(double sx) {
		return centerX + (sx - mapCenterX()) / zoom;
	}

	private double worldZ(double sy) {
		return centerZ + (sy - mapCenterY()) / zoom;
	}

	// ---- Widgets ----

	/** The bottom buttons are narrow, so they have short labels. */
	private static boolean compact;

	private static Text minimapLabel() {
		return Text.translatable((BIConfig.INSTANCE.minimap ? "baseinsurance.map.minimap_hide" : "baseinsurance.map.minimap_show") + (compact ? "_short" : ""));
	}

	/** Zooms out (or in) to fit everything you've ever explored in this dimension. */
	void showAll() {
		int[] b = BaseInsuranceClient.INSTANCE.worldMap.exploredBounds();
		if (b == null) return;
		following = false;
		centerX = (b[0] + b[2]) / 2.0;
		centerZ = (b[1] + b[3]) / 2.0;
		zoom = MathHelper.clamp(Math.min((mapRight() - 40) / (double) Math.max(1, b[2] - b[0]), (height - 80) / (double) Math.max(1, b[3] - b[1])), MIN_ZOOM, MAX_ZOOM);
	}

	private void openField(Field mode, String text) {
		if (topField == null) return;
		fieldMode = mode;
		topField.visible = true;
		topField.setText(text);
		topField.setPlaceholder(Text.translatable(mode == Field.GOTO ? "baseinsurance.map.go_to_hint" : "baseinsurance.map.pin_hint").withColor(0xFF707080));
		setFocused(topField);
		topField.setFocused(true);
	}

	private void closeField() {
		fieldMode = Field.NONE;
		if (topField != null) {
			topField.visible = false;
			topField.setFocused(false);
		}
		setFocused(null);
	}

	/** Enter in the top box: go to the coordinates, or save the pin. */
	private void submitField() {
		String text = topField.getText().trim();
		if (fieldMode == Field.PIN) {
			MapPins.add(text.isEmpty() ? "Pin " + (MapPins.count() + 1) : text, pinX, pinZ, BaseManager.dimensionKey(client.world));
			closeField();
			return;
		}
		int[] at = parseCoords(text);
		if (at == null) {
			topField.setEditableColor(0xFFFF5050);
			return;
		}
		topField.setEditableColor(0xFFE0E0E0);
		following = false;
		centerX = at[0] + 0.5;
		centerZ = at[1] + 0.5;
		gotoMark = at;
		zoom = Math.max(zoom, 0.5);
		closeField();
	}

	/** "100 200", "100, 64, 200" or "x 100 z 200": x and z. Null if it isn't coordinates. */
	static int[] parseCoords(String text) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(text.replace("~", ""));
		List<Double> n = new ArrayList<>();
		while (m.find()) n.add(Double.parseDouble(m.group()));
		if (n.size() == 2) return new int[] {(int) Math.floor(n.get(0)), (int) Math.floor(n.get(1))};
		if (n.size() == 3) return new int[] {(int) Math.floor(n.get(0)), (int) Math.floor(n.get(2))};
		return null;
	}

	@Override
	protected void init() {
		if (!createMode) {
			// Center, Show all, Go to, New base, Minimap on/off, Minimap size ... Done: as wide as fits, up to 90 each.
			int bw = Math.max(36, Math.min(90, (width - 12 - 6 * 4) / 7));
			int by = height - 26;
			// Short labels when the buttons are narrow, so none is cut off; the tooltips say the rest.
			compact = bw < 76;
			addDrawableChild(ButtonWidget.builder(Text.translatable(compact ? "baseinsurance.map.center_short" : "baseinsurance.map.center"), b -> centerOnPlayer())
					.tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("baseinsurance.map.center"))).dimensions(6, by, bw, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.translatable("baseinsurance.map.show_all"), b -> showAll())
					.tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("baseinsurance.map.show_all_tip"))).dimensions(6 + (bw + 4), by, bw, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.translatable("baseinsurance.map.go_to"), b -> openField(Field.GOTO, ""))
					.tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("baseinsurance.map.go_to_hint"))).dimensions(6 + 2 * (bw + 4), by, bw, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.translatable(compact ? "baseinsurance.map.new_base_short" : "baseinsurance.map.new_base"),
					b -> client.setScreen(forCreate(Commands.defaultName())))
					.dimensions(6 + 3 * (bw + 4), by, bw, 20).build());
			minimapButton = addDrawableChild(ButtonWidget.builder(minimapLabel(), b -> toggleMinimap())
					.tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("baseinsurance.map.minimap_tip"))).dimensions(6 + 4 * (bw + 4), by, bw, 20).build());
			sizeSlider = addDrawableChild(minimapSizeSlider(6 + 5 * (bw + 4), by, bw));
			sizeSlider.active = BIConfig.INSTANCE.minimap;
			addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
					.dimensions(width - 6 - bw, by, bw, 20).build());
			int fw = Math.min(260, width - 40);
			topField = new TextFieldWidget(textRenderer, width / 2 - fw / 2, 34, fw, 16, Text.translatable("baseinsurance.map.go_to"));
			topField.setMaxLength(48);
			topField.visible = fieldMode != Field.NONE;
			addDrawableChild(topField);
			if (fieldMode != Field.NONE) setFocused(topField);
			return;
		}

		int x = width - PANEL_WIDTH - 2;
		int w = PANEL_WIDTH - 6;
		if (editing != null) {
			if (fitZoom) {
				fitZoom = false;
				int across = editing.maxX - editing.minX + 1, deep = editing.maxZ - editing.minZ + 1;
				zoom = MathHelper.clamp(Math.min((mapRight() - 80) / (double) across, (height - 80) / (double) deep), MIN_ZOOM, MAX_ZOOM);
			}
			int bottom = client.world.getBottomY(), top = client.world.getTopYInclusive();
			addDrawableChild(slider(x, 88, w, "bottom", bottom, top, () -> editMinY, v -> editMinY = Math.min(v, editMaxY)));
			addDrawableChild(slider(x, 112, w, "top", bottom, top, () -> editMaxY, v -> editMaxY = Math.max(v, editMinY)));
			confirmButton = addDrawableChild(ButtonWidget.builder(Text.translatable("baseinsurance.map.save_size"), b -> confirm())
					.dimensions(x, height - 50, w, 20).build());
			addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, b -> close())
					.dimensions(x, height - 26, w, 20).build());
			updateConfirm();
			return;
		}
		nameField = new TextFieldWidget(textRenderer, x + 1, 42, w - 2, 16, Text.translatable("baseinsurance.map.name"));
		nameField.setMaxLength(32);
		nameField.setText(nameField.getText().isEmpty() && initialName != null ? initialName : nameField.getText());
		nameField.setChangedListener(t -> updateConfirm());
		addDrawableChild(nameField);
		addDrawableChild(slider(x, 88, w, "down", 0, 192, () -> down, v -> down = v));
		addDrawableChild(slider(x, 112, w, "up", 0, 320, () -> up, v -> up = v));
		confirmButton = addDrawableChild(ButtonWidget.builder(Text.translatable("baseinsurance.map.confirm"), b -> confirm())
				.dimensions(x, height - 50, w, 20).build());
		addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, b -> close())
				.dimensions(x, height - 26, w, 20).build());
		setInitialFocus(nameField);
		updateConfirm();
	}

	@Override
	public void removed() {
		super.removed();
		gotoMark = null;
		// Keep the zoom for next game (not the zoom a base was fitted to while making or resizing one).
		if (!createMode && editing == null && zoom != BIConfig.INSTANCE.mapZoom) {
			BIConfig.INSTANCE.mapZoom = zoom;
			BIConfig.save();
		}
	}

	SliderWidget sizeSlider;
	/** The size the minimap's outline was last drawn at (0: not drawn); for the self-test. */
	int previewDrawnAt;
	/** Until when the minimap's outline shows on the map (just after its size changes). */
	private long sizePreviewUntil;

	/** The minimap's size, saved as it changes; its outline shows where it'll be. */
	private SliderWidget minimapSizeSlider(int x, int y, int w) {
		int min = 64, max = 256;
		return new SliderWidget(x, y, w, 20, Text.empty(), (BIConfig.INSTANCE.minimapSize - min) / (double) (max - min)) {
			{
				updateMessage();
			}

			private int current() {
				// Steps of 4, so it's easy to land on a size.
				return MathHelper.clamp(min + (int) Math.round(MathHelper.clamp(value, 0, 1) * (max - min) / 4.0) * 4, min, max);
			}

			@Override
			protected void updateMessage() {
				setMessage(Text.translatable("baseinsurance.map.minimap_size", current()));
			}

			@Override
			protected void applyValue() {
				setMinimapSize(current());
			}
		};
	}

	/** The Minimap on/off button: saved at once, so it stays that way in every game after. */
	void toggleMinimap() {
		BIConfig.INSTANCE.minimap = !BIConfig.INSTANCE.minimap;
		BIConfig.save();
		if (minimapButton != null) minimapButton.setMessage(minimapLabel());
		if (sizeSlider != null) sizeSlider.active = BIConfig.INSTANCE.minimap;
	}

	/** The size slider: saved at once, with the outline shown a moment. */
	void setMinimapSize(int size) {
		if (BIConfig.INSTANCE.minimapSize == size) return;
		BIConfig.INSTANCE.minimapSize = size;
		BIConfig.save();
		sizePreviewUntil = System.currentTimeMillis() + 2500;
	}

	/** The bottom row's buttons don't overlap and all fit on screen; used by the self-test. */
	boolean bottomRowFits() {
		List<net.minecraft.client.gui.widget.ClickableWidget> row = new ArrayList<>();
		for (var child : children()) {
			if (child instanceof net.minecraft.client.gui.widget.ClickableWidget w && w.getY() == height - 26) row.add(w);
		}
		row.sort(java.util.Comparator.comparingInt(net.minecraft.client.gui.widget.ClickableWidget::getX));
		for (int i = 0; i < row.size(); i++) {
			var w = row.get(i);
			if (w.getX() < 0 || w.getX() + w.getWidth() > width) return false;
			if (i > 0 && row.get(i - 1).getX() + row.get(i - 1).getWidth() > w.getX()) return false;
		}
		return row.size() == 7;
	}

	private SliderWidget slider(int x, int y, int w, String key, int min, int max, IntSupplier getter, IntConsumer setter) {
		return new SliderWidget(x, y, w, 20, Text.empty(), (getter.getAsInt() - min) / (double) (max - min)) {
			{
				updateMessage();
			}

			private int current() {
				return min + (int) Math.round(MathHelper.clamp(value, 0, 1) * (max - min));
			}

			@Override
			protected void updateMessage() {
				setMessage(Text.translatable("baseinsurance.map." + key, current()));
			}

			@Override
			protected void applyValue() {
				setter.accept(current());
				updateConfirm();
			}
		};
	}

	private int minY() {
		if (editing != null) return editMinY;
		return Math.max(client.world.getBottomY(), baseY - down);
	}

	private int maxY() {
		if (editing != null) return editMaxY;
		return Math.min(client.world.getTopYInclusive(), baseY + up);
	}

	private long volume() {
		return (long) (selMaxX - selMinX + 1) * (maxY() - minY() + 1) * (selMaxZ - selMinZ + 1);
	}

	private void updateConfirm() {
		if (confirmButton == null) return;
		error = editing != null ? Commands.validateSize(volume()) : Commands.validate(nameField.getText(), volume());
		confirmButton.active = error == null;
	}

	/** Creates the base from the drawn area. Public so the self-test can press it. */
	public void confirm() {
		updateConfirm();
		if (error != null) return;
		if (editing != null) {
			Commands.resizeBase(editing, selMinX, selMaxX, selMinZ, selMaxZ, minY(), maxY(), Alerts::chat);
			client.setScreen(new ReportScreen(editing));
			return;
		}
		String result = Commands.createBase(nameField.getText(), client.world, client.player.getBlockPos(),
				selMinX, selMaxX, selMinZ, selMaxZ, minY(), maxY(), Alerts::chat);
		if (result != null) {
			error = result;
			return;
		}
		close();
	}

	private void centerOnPlayer() {
		if (client.player == null) return;
		centerX = client.player.getX();
		centerZ = client.player.getZ();
		following = true;
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (ModsPanel.streamSafe()) return true;
		double mx = click.x(), my = click.y();
		if (menu != null) {
			// A click on a row does it; anywhere else just closes the menu.
			for (int[] row : menuRows) {
				if (mx >= row[0] && mx < row[2] && my >= row[1] && my < row[3]) {
					menuPick(row[4]);
					return true;
				}
			}
			menu = null;
			return true;
		}
		if (super.mouseClicked(click, doubled)) return true;
		if (fieldMode != Field.NONE) closeField();
		if (mx >= mapRight()) return false;
		// Right-click: walk to the spot, copy where it is, pin it.
		if (click.button() == 1 && !createMode) {
			openMenu(mx, my);
			return true;
		}
		ruler = null;
		pressX = mx;
		pressY = my;
		moved = false;

		// The list in the corner: click to jump to a base or a pin.
		for (int[] row : baseListRows) {
			if (my >= row[1] && my < row[1] + 11 && mx >= 6 && mx < row[2]) {
				following = false;
				if (row[3] == 0) {
					Base base = BaseInsuranceClient.INSTANCE.manager.all().get(row[0]);
					centerX = (base.minX + base.maxX + 1) / 2.0;
					centerZ = (base.minZ + base.maxZ + 1) / 2.0;
				} else {
					MapPins.Pin pin = listedPins.get(row[0]);
					centerX = pin.x + 0.5;
					centerZ = pin.z + 0.5;
					zoom = Math.max(zoom, 0.5);
				}
				return true;
			}
		}

		// Shift-drag: measure.
		if (!createMode && click.hasShift()) {
			drag = Drag.RULER;
			ruler = new double[] {worldX(mx), worldZ(my), worldX(mx), worldZ(my)};
			return true;
		}
		drag = createMode ? handleAt(mx, my) : Drag.PAN;
		dragStart = new int[]{selMinX, selMaxX, selMinZ, selMaxZ, (int) Math.floor(worldX(mx)), (int) Math.floor(worldZ(my))};
		return true;
	}

	/** Opens the right-click menu for the spot under the mouse. */
	void openMenu(double mx, double my) {
		menu = new int[] {(int) mx, (int) my, (int) Math.floor(worldX(mx)), (int) Math.floor(worldZ(my))};
		menuNote = null;
		menuPin = pinAt(mx, my);
		drag = Drag.NONE;
	}

	/** Self-test: right-clicks the map where block x, z is drawn. */
	void testRightClickAt(int bx, int bz) {
		mouseClicked(new Click(screenX(bx + 0.5), screenY(bz + 0.5), new net.minecraft.client.input.MouseInput(1, 0)), false);
	}

	/** A menu row: 0 walks there with Baritone, 1 copies the coordinates, 2 pins it, 3 removes the pin, 4 measures from you. */
	void menuPick(int which) {
		int x = menu[2], z = menu[3];
		if (which == 2) {
			menu = null;
			pinX = x;
			pinZ = z;
			openField(Field.PIN, "");
			return;
		}
		if (which == 3) {
			if (menuPin != null) MapPins.remove(menuPin);
			menu = null;
			return;
		}
		if (which == 4) {
			if (client.player != null) ruler = new double[] {client.player.getX(), client.player.getZ(), x + 0.5, z + 0.5};
			menu = null;
			return;
		}
		if (which == 1) {
			client.keyboard.setClipboard(x + " " + z);
			if (client.player != null) client.player.sendMessage(Text.literal("Copied " + x + " " + z), true);
			menu = null;
			return;
		}
		String said = Travel.go(client, x, z);
		if (!said.startsWith("Going")) {
			menuNote = said;
			return;
		}
		// Back to the game to watch it go.
		menu = null;
		close();
		if (client.player != null) client.player.sendMessage(Text.literal(said), true);
	}

	private void drawMenu(DrawContext context, int mouseX, int mouseY) {
		menuRows.clear();
		TextRenderer font = textRenderer;
		boolean can = Travel.available();
		String title = (menuPin != null ? menuPin.name + ":  " : "") + "X " + menu[2] + "  Z " + menu[3];
		// What's worth knowing about the spot: how far, which way, and the same place in the other dimension.
		List<String> facts = new ArrayList<>();
		if (client.player != null) {
			double dx = menu[2] + 0.5 - client.player.getX(), dz = menu[3] + 0.5 - client.player.getZ();
			facts.add(String.format(Locale.ROOT, "%,d blocks %s of you", Math.round(Math.hypot(dx, dz)), compass(dx, dz)));
		}
		String dim = BaseManager.dimensionKey(client.world);
		if (dim.equals("minecraft:overworld")) facts.add(String.format(Locale.ROOT, "In the Nether: X %d  Z %d", Math.floorDiv(menu[2], 8), Math.floorDiv(menu[3], 8)));
		else if (dim.equals("minecraft:the_nether")) facts.add(String.format(Locale.ROOT, "In the Overworld: X %d  Z %d", menu[2] * 8, menu[3] * 8));
		List<String> labels = new ArrayList<>(List.of(can ? "Walk here" : "Walk here (needs Baritone)", "Copy coordinates", "Pin here…"));
		List<Integer> ids = new ArrayList<>(List.of(0, 1, 2));
		if (menuPin != null) {
			labels.add("Remove this pin");
			ids.add(3);
		}
		labels.add("Measure from me");
		ids.add(4);
		String note = menuNote;
		int w = font.getWidth(title);
		for (String l : labels) w = Math.max(w, font.getWidth(l));
		for (String f : facts) w = Math.max(w, font.getWidth(f));
		w += 20;
		if (note != null) w = Math.max(w, Math.min(220, font.getWidth(note) + 12));
		List<net.minecraft.text.OrderedText> noteLines = note == null ? List.of() : font.wrapLines(Text.literal(note), w - 12);
		int rowH = 13;
		int h = 16 + facts.size() * 10 + 2 + labels.size() * rowH + 4 + noteLines.size() * 10;
		int x = Math.max(4, Math.min(menu[0], width - w - 4)), y = Math.max(4, Math.min(menu[1], height - h - 4));
		context.fill(x, y, x + w, y + h, 0xF0181820);
		context.drawStrokedRectangle(x, y, w, h, 0xFF6A6AB4);
		context.drawTextWithShadow(font, title, x + 6, y + 5, GOLD);
		int ry = y + 16;
		for (String f : facts) {
			context.drawTextWithShadow(font, f, x + 6, ry, GRAY);
			ry += 10;
		}
		ry += 2;
		for (int i = 0; i < labels.size(); i++) {
			int id = ids.get(i);
			boolean enabled = id != 0 || can;
			boolean hover = enabled && mouseX >= x + 2 && mouseX < x + w - 2 && mouseY >= ry && mouseY < ry + rowH;
			if (hover) context.fill(x + 2, ry, x + w - 2, ry + rowH, 0xC0303048);
			context.drawTextWithShadow(font, labels.get(i), x + 8, ry + 3, !enabled ? 0xFF707080 : hover ? 0xFFFFFFFF : 0xFFD0D0E0);
			if (enabled) menuRows.add(new int[] {x + 2, ry, x + w - 2, ry + rowH, id});
			ry += rowH;
		}
		ry += 2;
		for (var line : noteLines) {
			context.drawTextWithShadow(font, line, x + 6, ry, 0xFFFF7070);
			ry += 10;
		}
	}

	/** Which part of the proposed area (if any) is under the mouse. */
	private Drag handleAt(double mx, double my) {
		double x0 = screenX(selMinX), x1 = screenX(selMaxX + 1), y0 = screenY(selMinZ), y1 = screenY(selMaxZ + 1);
		double tolerance = 6;
		boolean nearW = Math.abs(mx - x0) <= tolerance, nearE = Math.abs(mx - x1) <= tolerance;
		boolean nearN = Math.abs(my - y0) <= tolerance, nearS = Math.abs(my - y1) <= tolerance;
		boolean withinX = mx >= x0 - tolerance && mx <= x1 + tolerance;
		boolean withinY = my >= y0 - tolerance && my <= y1 + tolerance;
		if (nearN && nearW) return Drag.NW;
		if (nearN && nearE) return Drag.NE;
		if (nearS && nearW) return Drag.SW;
		if (nearS && nearE) return Drag.SE;
		if (nearN && withinX) return Drag.N;
		if (nearS && withinX) return Drag.S;
		if (nearW && withinY) return Drag.W;
		if (nearE && withinY) return Drag.E;
		if (mx > x0 && mx < x1 && my > y0 && my < y1) return Drag.MOVE;
		return Drag.PAN;
	}

	@Override
	public boolean mouseDragged(Click click, double dx, double dy) {
		if (drag == Drag.NONE) return super.mouseDragged(click, dx, dy);
		if (Math.abs(click.x() - pressX) + Math.abs(click.y() - pressY) > 3) moved = true;
		int wx = (int) Math.floor(worldX(click.x()));
		int wz = (int) Math.floor(worldZ(click.y()));
		int ox = wx - dragStart[4], oz = wz - dragStart[5];
		switch (drag) {
			case RULER -> {
				ruler[2] = worldX(click.x());
				ruler[3] = worldZ(click.y());
				return true;
			}
			case PAN -> {
				if (moved) following = false;
				centerX -= dx / zoom;
				centerZ -= dy / zoom;
			}
			case MOVE -> {
				selMinX = dragStart[0] + ox;
				selMaxX = dragStart[1] + ox;
				selMinZ = dragStart[2] + oz;
				selMaxZ = dragStart[3] + oz;
			}
			default -> {
				if (drag == Drag.W || drag == Drag.NW || drag == Drag.SW) selMinX = Math.min(wx, selMaxX - MIN_SIZE + 1);
				if (drag == Drag.E || drag == Drag.NE || drag == Drag.SE) selMaxX = Math.max(wx, selMinX + MIN_SIZE - 1);
				if (drag == Drag.N || drag == Drag.NW || drag == Drag.NE) selMinZ = Math.min(wz, selMaxZ - MIN_SIZE + 1);
				if (drag == Drag.S || drag == Drag.SW || drag == Drag.SE) selMaxZ = Math.max(wz, selMinZ + MIN_SIZE - 1);
			}
		}
		updateConfirm();
		return true;
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (drag == Drag.PAN && !moved && !createMode) {
			// A click (not a drag) on a base opens its report.
			Base base = baseAt(click.x(), click.y());
			if (base != null) {
				drag = Drag.NONE;
				client.setScreen(new ReportScreen(base));
				return true;
			}
		}
		if (drag == Drag.PAN && !moved && !createMode) {
			// A click on a pin zooms in on it.
			MapPins.Pin pin = pinAt(click.x(), click.y());
			if (pin != null) {
				following = false;
				centerX = pin.x + 0.5;
				centerZ = pin.z + 0.5;
				zoom = Math.max(zoom, 1);
			}
		}
		drag = Drag.NONE;
		return super.mouseReleased(click);
	}

	private Base baseAt(double mx, double my) {
		int wx = (int) Math.floor(worldX(mx)), wz = (int) Math.floor(worldZ(my));
		for (Base base : bases()) {
			if (wx >= base.minX && wx <= base.maxX && wz >= base.minZ && wz <= base.maxZ) return base;
		}
		return null;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (mouseX >= mapRight()) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		double beforeX = worldX(mouseX), beforeZ = worldZ(mouseY);
		zoom = MathHelper.clamp(zoom * Math.pow(1.25, verticalAmount), MIN_ZOOM, MAX_ZOOM);
		// While following you, zoom around you; otherwise around the mouse.
		if (following) return true;
		centerX = beforeX - (mouseX - mapCenterX()) / zoom;
		centerZ = beforeZ - (mouseY - mapCenterY()) / zoom;
		return true;
	}

	private List<Base> bases() {
		return BaseInsuranceClient.INSTANCE.manager.inDimension(BaseManager.dimensionKey(client.world));
	}

	// ---- Drawing ----

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, width, height, 0xFF0B0B12);
		int right = mapRight();
		if (following && client.player != null && drag == Drag.NONE) {
			var pos = client.player.getLerpedPos(delta);
			centerX = pos.x;
			centerZ = pos.z;
		}
		context.enableScissor(0, 0, right, height);
		MapRender.drawTerrain(context, mapCenterX(), mapCenterY(), centerX, centerZ, zoom, right / 2.0, height / 2.0);
		drawGrid(context, right);
		for (Base base : bases()) {
			if (base != editing) drawBase(context, base);
		}
		if (createMode) drawSelection(context);
		drawTrustCircle(context);
		drawMarkers(context);
		drawPins(context);
		drawRuler(context);
		drawOtherPlayers(context, delta);
		drawPlayer(context, delta);
		context.disableScissor();
		drawOverlayText(context, mouseX, mouseY, right);
		if (createMode) drawPanel(context);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		if (ModsPanel.streamSafe()) {
			context.fill(0, 0, width, height, 0xF0101418);
			context.drawCenteredTextWithShadow(textRenderer, "Hidden while stream-safe mode is on", width / 2, height / 2 - 10, 0xFFFFFFFF);
			context.drawCenteredTextWithShadow(textRenderer, "The map gives away where you are. Press Esc, then the stream-safe key to show it.", width / 2, height / 2 + 4, 0xFF9AA4AE);
			return;
		}
		if (menu != null) {
			drawMenu(context, mouseX, mouseY);
			return;
		}
		// While the size is being set: an outline of the minimap where it shows in game.
		if (sizeSlider != null && BIConfig.INSTANCE.minimap && (sizeSlider.isHovered() || sizeSlider.isFocused() || System.currentTimeMillis() < sizePreviewUntil)) {
			int[] spot = Minimap.spot(width);
			previewDrawnAt = spot[2];
			context.fill(spot[0], spot[1], spot[0] + spot[2], spot[1] + spot[2], 0x40000000);
			context.drawStrokedRectangle(spot[0] - 2, spot[1] - 2, spot[2] + 4, spot[2] + 4, 0xFFFFD866);
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable("baseinsurance.map.minimap_here"), spot[0] + spot[2] / 2, spot[1] + spot[2] / 2 - 4, 0xFFFFD866);
		}
		if (!createMode && drag == Drag.NONE && mouseX < mapRight()) {
			MapPins.Pin own = pinAt(mouseX, mouseY);
			MapMarkers.Marker pin = own == null ? markerAt(mouseX, mouseY) : null;
			Base base = pin == null && own == null ? baseAt(mouseX, mouseY) : null;
			if (own != null) {
				List<Text> lines = new ArrayList<>();
				lines.add(Text.literal(own.name).withColor(own.color()));
				lines.add(Text.literal("X " + own.x + "  Z " + own.z).withColor(0xFFB0B0C0));
				if (client.player != null) {
					double dx = own.x + 0.5 - client.player.getX(), dz = own.z + 0.5 - client.player.getZ();
					lines.add(Text.literal(String.format(Locale.ROOT, "%,d blocks %s", Math.round(Math.hypot(dx, dz)), compass(dx, dz))).withColor(0xFFB0B0C0));
				}
				lines.add(Text.literal("Right-click to remove or walk there").withColor(0xFF707080));
				context.drawTooltip(textRenderer, lines, mouseX, mouseY);
			} else if (pin != null) {
				List<Text> lines = new ArrayList<>();
				lines.add(Text.literal(pin.label()).withColor(pin.color()));
				if (!pin.detail().isEmpty()) lines.add(Text.literal(pin.detail()).withColor(0xFFB0B0C0));
				context.drawTooltip(textRenderer, lines, mouseX, mouseY);
			} else if (base != null) {
				Text tip = base.damageCount() == 0
						? Text.translatable("baseinsurance.map.tip_intact", base.name)
						: Text.translatable("baseinsurance.map.tip_damage", base.name, Alerts.plural(base.damageCount(), "change"));
				context.drawTooltip(textRenderer, tip, mouseX, mouseY);
			}
		}
	}

	/** Chunk lines when zoomed in far enough to be useful. */
	private void drawGrid(DrawContext context, int right) {
		if (zoom < 3 || !BIConfig.INSTANCE.mapGrid) return;
		int x0 = (int) Math.floor(worldX(0)) >> 4, x1 = (int) Math.floor(worldX(right)) >> 4;
		int z0 = (int) Math.floor(worldZ(0)) >> 4, z1 = (int) Math.floor(worldZ(height)) >> 4;
		for (int cx = x0; cx <= x1 + 1; cx++) {
			int sx = (int) screenX(cx << 4);
			context.fill(sx, 0, sx + 1, height, 0x18FFFFFF);
		}
		for (int cz = z0; cz <= z1 + 1; cz++) {
			int sy = (int) screenY(cz << 4);
			context.fill(0, sy, right, sy + 1, 0x18FFFFFF);
		}
	}

	private void drawBase(DrawContext context, Base base) {
		int x0 = (int) screenX(base.minX), x1 = (int) screenX(base.maxX + 1);
		int y0 = (int) screenY(base.minZ), y1 = (int) screenY(base.maxZ + 1);
		boolean damaged = base.damageCount() > 0;
		int color = damaged ? 0xFFFF5050 : 0xFF50FF70;
		context.fill(x0, y0, x1, y1, (color & 0xFFFFFF) | 0x28000000);
		// Shade the parts not seen up close yet: the darker, the less has been seen.
		for (var column : base.coverage().entrySet()) {
			float seen = column.getValue();
			if (seen >= 0.95f) continue;
			int cx = net.minecraft.util.math.ChunkPos.getPackedX(column.getKey()), cz = net.minecraft.util.math.ChunkPos.getPackedZ(column.getKey());
			int sx0 = (int) screenX(Math.max(cx << 4, base.minX)), sx1 = (int) screenX(Math.min((cx << 4) + 16, base.maxX + 1));
			int sy0 = (int) screenY(Math.max(cz << 4, base.minZ)), sy1 = (int) screenY(Math.min((cz << 4) + 16, base.maxZ + 1));
			int alpha = (int) ((1 - seen) * 150);
			context.fill(sx0, sy0, sx1, sy1, alpha << 24);
		}
		context.drawStrokedRectangle(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0), color);
		int percent = base.snapshotPercent();
		String label = (damaged ? base.name + " ⚠" + base.damageCount() : base.name) + (percent < 100 ? "  ◔" + percent + "%" : "");
		int w = textRenderer.getWidth(label);
		int lx = (x0 + x1) / 2 - w / 2;
		int ly = y0 - 11;
		context.fill(lx - 2, ly - 1, lx + w + 2, ly + 9, 0xA0000000);
		context.drawTextWithShadow(textRenderer, label, lx, ly, color);

		// Damage as dots, when zoomed in enough to tell them apart.
		if (damaged && zoom >= 1 && BIConfig.INSTANCE.mapDamageDots) {
			int shown = 0;
			for (var entry : base.changes.entrySet()) {
				long key = entry.getKey();
				int sx = (int) screenX(BlockPos.unpackLongX(key)), sy = (int) screenY(BlockPos.unpackLongZ(key));
				int size = Math.max(1, (int) zoom);
				context.fill(sx, sy, sx + size, sy + size, 0xFF000000 | Overlay.color(entry.getValue().kind()));
				if (++shown > 20000) break;
			}
		}
	}

	private void drawSelection(DrawContext context) {
		int x0 = (int) screenX(selMinX), x1 = (int) screenX(selMaxX + 1);
		int y0 = (int) screenY(selMinZ), y1 = (int) screenY(selMaxZ + 1);
		context.fill(x0, y0, x1, y1, 0x3040E0FF);
		context.drawStrokedRectangle(x0, y0, x1 - x0, y1 - y0, ACCENT);
		int[][] handles = {{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}, {(x0 + x1) / 2, y0}, {(x0 + x1) / 2, y1}, {x0, (y0 + y1) / 2}, {x1, (y0 + y1) / 2}};
		for (int[] h : handles) {
			context.fill(h[0] - 3, h[1] - 3, h[0] + 3, h[1] + 3, 0xFF000000);
			context.fill(h[0] - 2, h[1] - 2, h[0] + 2, h[1] + 2, ACCENT);
		}
		String size = (selMaxX - selMinX + 1) + " × " + (selMaxZ - selMinZ + 1);
		int w = textRenderer.getWidth(size);
		int ly = y1 - y0 > 24 ? y0 + 6 : y0 - 12;
		context.fill((x0 + x1) / 2 - w / 2 - 2, ly - 1, (x0 + x1) / 2 + w / 2 + 2, ly + 9, 0xA0000000);
		context.drawTextWithShadow(textRenderer, size, (x0 + x1) / 2 - w / 2, ly, WHITE);
	}

	/** Pins from other mods, drawn this frame, for hovering. */
	private List<MapMarkers.Marker> markers = List.of();

	/** Pins from other mods (where Naughty or Nice last saw players) as small diamonds; names when zoomed in. */
	private void drawMarkers(DrawContext context) {
		markers = BIConfig.INSTANCE.mapPins && viewDimension != null ? MapMarkers.in(viewDimension) : List.of();
		for (MapMarkers.Marker pin : markers) {
			int sx = (int) screenX(pin.x()), sy = (int) screenY(pin.z());
			for (int i = 0; i <= 3; i++) context.fill(sx - i, sy - 3 + i, sx + i + 1, sy - 2 + i, 0xFF000000);
			for (int i = 0; i <= 3; i++) context.fill(sx - i, sy + 3 - i, sx + i + 1, sy + 4 - i, 0xFF000000);
			for (int i = 0; i <= 2; i++) context.fill(sx - i, sy - 2 + i, sx + i + 1, sy - 1 + i, pin.color());
			for (int i = 0; i <= 2; i++) context.fill(sx - i, sy + 2 - i, sx + i + 1, sy + 3 - i, pin.color());
			if (zoom >= 1 && !pin.label().isEmpty()) {
				int w = textRenderer.getWidth(pin.label());
				context.fill(sx - w / 2 - 2, sy + 5, sx + w / 2 + 2, sy + 15, 0xA0000000);
				context.drawTextWithShadow(textRenderer, pin.label(), sx - w / 2, sy + 6, pin.color());
			}
		}
	}

	/** Your pins (gold flags), the spots you died (red crosses), and where Go to went (a cyan ring). */
	private void drawPins(DrawContext context) {
		if (viewDimension == null) return;
		for (MapPins.Pin pin : MapPins.in(viewDimension)) {
			int sx = (int) screenX(pin.x + 0.5), sy = (int) screenY(pin.z + 0.5);
			if (pin.death) {
				for (int i = -3; i <= 3; i++) {
					context.fill(sx + i - 1, sy + i - 1, sx + i + 2, sy + i + 2, 0xFF000000);
					context.fill(sx + i - 1, sy - i - 1, sx + i + 2, sy - i + 2, 0xFF000000);
				}
				for (int i = -3; i <= 3; i++) {
					context.fill(sx + i, sy + i, sx + i + 1, sy + i + 1, pin.color());
					context.fill(sx + i, sy - i, sx + i + 1, sy - i + 1, pin.color());
				}
			} else {
				context.fill(sx - 1, sy - 9, sx + 2, sy + 1, 0xFF000000);
				context.fill(sx, sy - 8, sx + 1, sy, 0xFFE0E0E0);
				context.fill(sx + 1, sy - 9, sx + 7, sy - 3, 0xFF000000);
				context.fill(sx + 1, sy - 8, sx + 6, sy - 4, pin.color());
			}
			if (zoom >= 0.25) {
				int w = textRenderer.getWidth(pin.name);
				context.fill(sx - w / 2 - 2, sy + 4, sx + w / 2 + 2, sy + 14, 0xA0000000);
				context.drawTextWithShadow(textRenderer, pin.name, sx - w / 2, sy + 5, pin.color());
			}
		}
		if (gotoMark != null) {
			int sx = (int) screenX(gotoMark[0] + 0.5), sy = (int) screenY(gotoMark[1] + 0.5);
			for (int a = 0; a < 32; a++) {
				double t = a / 32.0 * Math.PI * 2;
				int x = (int) Math.round(sx + Math.cos(t) * 6), y = (int) Math.round(sy + Math.sin(t) * 6);
				context.fill(x, y, x + 1, y + 1, ACCENT);
			}
		}
	}

	private MapPins.Pin pinAt(double mx, double my) {
		if (viewDimension == null) return null;
		MapPins.Pin best = null;
		double bestDistance = 7;
		for (MapPins.Pin pin : MapPins.in(viewDimension)) {
			double d = Math.hypot(screenX(pin.x + 0.5) - mx, screenY(pin.z + 0.5) - my - (pin.death ? 0 : -4));
			if (d < bestDistance) {
				best = pin;
				bestDistance = d;
			}
		}
		return best;
	}

	/** The ruler: a line with how far it is, which way, and how long it takes to get there. */
	private void drawRuler(DrawContext context) {
		if (ruler == null) return;
		double x0 = screenX(ruler[0]), y0 = screenY(ruler[1]), x1 = screenX(ruler[2]), y1 = screenY(ruler[3]);
		double length = Math.hypot(x1 - x0, y1 - y0);
		int steps = (int) Math.min(4000, Math.max(1, length));
		for (int i = 0; i <= steps; i++) {
			int x = (int) Math.round(x0 + (x1 - x0) * i / steps), y = (int) Math.round(y0 + (y1 - y0) * i / steps);
			context.fill(x, y, x + 1, y + 1, (i / 4) % 2 == 0 ? 0xFFFFFFFF : 0xFF000000);
		}
		double dx = ruler[2] - ruler[0], dz = ruler[3] - ruler[1], blocks = Math.hypot(dx, dz);
		List<String> lines = new ArrayList<>();
		lines.add(String.format(Locale.ROOT, "%,d blocks %s", Math.round(blocks), compass(dx, dz)));
		lines.add("Sprint " + duration(blocks / 5.6) + " · Elytra " + duration(blocks / 30));
		if (BaseManager.dimensionKey(client.world).equals("minecraft:overworld") && blocks > 800) {
			lines.add(String.format(Locale.ROOT, "Through the Nether: %,d blocks (%s sprinting)", Math.round(blocks / 8), duration(blocks / 8 / 5.6)));
		}
		int w = 0;
		for (String l : lines) w = Math.max(w, textRenderer.getWidth(l));
		int lx = (int) Math.min(width - w - 8, Math.max(4, (x0 + x1) / 2 + 8)), ly = (int) Math.max(30, Math.min(height - 60, (y0 + y1) / 2 - 6));
		context.fill(lx - 3, ly - 3, lx + w + 3, ly + lines.size() * 10 + 1, 0xE0101018);
		for (int i = 0; i < lines.size(); i++) context.drawTextWithShadow(textRenderer, lines.get(i), lx, ly + i * 10, i == 0 ? WHITE : GRAY);
	}

	static String compass(double dx, double dz) {
		if (Math.abs(dx) < 1 && Math.abs(dz) < 1) return "here";
		String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
		double angle = Math.toDegrees(Math.atan2(-dx, dz));
		return names[Math.floorMod((int) Math.round(angle / 45), 8)];
	}

	static String duration(double seconds) {
		long s = Math.round(seconds);
		if (s < 60) return s + "s";
		if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
		return (s / 3600) + "h " + (s % 3600 / 60) + "m";
	}

	/** A scale bar: a round number of blocks that's a sensible width at this zoom. */
	private void drawScale(DrawContext context, int right) {
		double target = 80 / zoom;
		double step = Math.pow(10, Math.floor(Math.log10(target)));
		double blocks = target >= 5 * step ? 5 * step : target >= 2 * step ? 2 * step : step;
		int px = (int) Math.round(blocks * zoom);
		int x1 = right - 10, x0 = x1 - px, y = height - 46;
		String label = blocks >= 1000 ? String.format(Locale.ROOT, "%,d k", Math.round(blocks / 1000)) : String.format(Locale.ROOT, "%d", Math.round(blocks));
		label += " blocks";
		context.fill(x0 - 4, y - 12, x1 + 4, y + 4, PANEL);
		context.fill(x0, y, x1, y + 2, WHITE);
		context.fill(x0, y - 3, x0 + 1, y + 2, WHITE);
		context.fill(x1 - 1, y - 3, x1, y + 2, WHITE);
		context.drawTextWithShadow(textRenderer, label, x1 - textRenderer.getWidth(label), y - 11, GRAY);
	}

	private MapMarkers.Marker markerAt(double mx, double my) {
		MapMarkers.Marker best = null;
		double bestDistance = 6;
		for (MapMarkers.Marker pin : markers) {
			double d = Math.hypot(screenX(pin.x()) - mx, screenY(pin.z()) - my);
			if (d < bestDistance) {
				best = pin;
				bestDistance = d;
			}
		}
		return best;
	}

	/** Other players you can see, as dots with their names when zoomed in enough to read them. */
	private void drawOtherPlayers(DrawContext context, float delta) {
		for (var player : client.world.getPlayers()) {
			if (player == client.player) continue;
			var pos = player.getLerpedPos(delta);
			int sx = (int) screenX(pos.x), sy = (int) screenY(pos.z);
			context.fill(sx - 2, sy - 2, sx + 2, sy + 2, 0xFF000000);
			context.fill(sx - 1, sy - 1, sx + 1, sy + 1, 0xFFFFFFFF);
			if (zoom >= 0.5) {
				String name = player.getGameProfile().name();
				int w = textRenderer.getWidth(name);
				context.fill(sx - w / 2 - 2, sy - 13, sx + w / 2 + 2, sy - 3, 0xA0000000);
				context.drawTextWithShadow(textRenderer, name, sx - w / 2, sy - 12, 0xFFFFFFFF);
			}
		}
	}

	private void drawPlayer(DrawContext context, float delta) {
		if (client.player == null) return;
		var pos = client.player.getLerpedPos(delta);
		float sx = (float) screenX(pos.x), sy = (float) screenY(pos.z);
		Matrix3x2fStack matrices = context.getMatrices();
		matrices.pushMatrix();
		matrices.translate(sx, sy);
		matrices.rotate((float) Math.toRadians(client.player.getYaw() + 180));
		matrices.scale(1.5f, 1.5f);
		context.drawText(textRenderer, "▲", -textRenderer.getWidth("▲") / 2, -4, 0xFFFFFFFF, true);
		matrices.popMatrix();
	}

	private void drawOverlayText(DrawContext context, int mouseX, int mouseY, int right) {
		TextRenderer font = textRenderer;
		// Title, dimension and the coordinates under the cursor.
		String info = BaseManager.dimensionKey(client.world).replace("minecraft:", "").replace('_', ' ');
		if (mouseX < right) {
			info += String.format(Locale.ROOT, "  ·  X %d  Z %d", (int) Math.floor(worldX(mouseX)), (int) Math.floor(worldZ(mouseY)));
		}
		WorldMap map = BaseInsuranceClient.INSTANCE.worldMap;
		long explored = map.exploredBlocks();
		String known = explored <= 0 ? "" : explored >= 1_000_000
				? String.format(Locale.ROOT, "Explored about %.1f km² · %,d tiles", explored / 1_000_000.0, map.tileCount())
				: String.format(Locale.ROOT, "Explored about %,d blocks² · %,d tiles", explored, map.tileCount());
		int loading = map.stillLoading();
		if (loading > 0) known += (known.isEmpty() ? "" : "  ·  ") + "Filling in…";
		int boxW = Math.max(font.getWidth(title), Math.max(font.getWidth(info), font.getWidth(known)));
		context.fill(4, 4, 12 + boxW, known.isEmpty() ? 28 : 38, PANEL);
		context.drawTextWithShadow(font, title, 8, 7, GOLD);
		context.drawTextWithShadow(font, info, 8, 17, GRAY);
		if (!known.isEmpty()) context.drawTextWithShadow(font, known, 8, 27, loading > 0 ? 0xFFFFD866 : GRAY);
		if (!createMode) drawScale(context, right);

		// Base list.
		baseListRows.clear();
		listedPins.clear();
		if (!createMode) {
			int y = known.isEmpty() ? 34 : 44;
			List<Base> all = BaseInsuranceClient.INSTANCE.manager.all();
			String here = BaseManager.dimensionKey(client.world);
			for (int i = 0; i < all.size(); i++) {
				Base base = all.get(i);
				if (!base.dimension.equals(here)) continue;
				String line = (base.damageCount() == 0 ? "✔ " : "⚠ ") + base.name;
				int w = font.getWidth(line) + 8;
				boolean hover = mouseX >= 4 && mouseX < 4 + w && mouseY >= y - 1 && mouseY < y + 10;
				context.fill(4, y - 1, 4 + w, y + 10, hover ? 0xC0303048 : PANEL);
				context.drawTextWithShadow(font, line, 8, y, base.damageCount() == 0 ? 0xFF50FF70 : 0xFFFF5050);
				baseListRows.add(new int[]{i, y, 4 + w, 0});
				y += 12;
			}
			// Pins and deaths below, newest first, as many as fit.
			List<MapPins.Pin> pins = new ArrayList<>(MapPins.in(here));
			pins.sort((a, b) -> Long.compare(b.time, a.time));
			for (MapPins.Pin pin : pins) {
				if (y > height - 70) break;
				String line = (pin.death ? "✖ " : "⚑ ") + pin.name;
				int w = font.getWidth(line) + 8;
				boolean hover = mouseX >= 4 && mouseX < 4 + w && mouseY >= y - 1 && mouseY < y + 10;
				context.fill(4, y - 1, 4 + w, y + 10, hover ? 0xC0303048 : PANEL);
				context.drawTextWithShadow(font, line, 8, y, pin.color());
				baseListRows.add(new int[]{listedPins.size(), y, 4 + w, 1});
				listedPins.add(pin);
				y += 12;
			}
		}

		// Help along the bottom, above the buttons, cut to fit.
		String help = Text.translatable(createMode ? "baseinsurance.map.help_create" : "baseinsurance.map.help").getString();
		if (font.getWidth(help) > right - 16) help = Text.translatable(createMode ? "baseinsurance.map.help_create_short" : "baseinsurance.map.help_short").getString();
		help = font.trimToWidth(help, right - 16);
		int y = createMode ? height - 14 : height - 40;
		context.fill(4, y - 2, 12 + font.getWidth(help), y + 10, PANEL);
		context.drawTextWithShadow(font, help, 8, y, GRAY);
	}

	private void drawPanel(DrawContext context) {
		TextRenderer font = textRenderer;
		int x0 = width - PANEL_WIDTH - 6, x1 = width;
		context.fill(x0, 0, x1, height, PANEL);
		context.fill(x0, 0, x0 + 1, height, BORDER);
		int x = x0 + 4;
		context.drawTextWithShadow(font, title, x, 8, GOLD);
		if (editing != null) {
			int wasAcross = editing.maxX - editing.minX + 1, wasDeep = editing.maxZ - editing.minZ + 1;
			context.drawTextWithShadow(font, Text.translatable("baseinsurance.map.was", wasAcross, wasDeep, editing.minY, editing.maxY), x, 31, GRAY);
			context.drawTextWithShadow(font, Text.translatable("baseinsurance.map.edit_hint"), x, 42, GRAY);
		} else {
			context.drawTextWithShadow(font, Text.translatable("baseinsurance.map.name"), x, 31, GRAY);
		}

		int across = selMaxX - selMinX + 1, depth = selMaxZ - selMinZ + 1, tall = maxY() - minY() + 1;
		context.drawTextWithShadow(font, Text.translatable("baseinsurance.map.area", across, depth), x, 64, WHITE);
		context.drawTextWithShadow(font, Text.translatable("baseinsurance.map.height", minY(), maxY(), tall), x, 75, WHITE);

		long volume = volume();
		context.drawTextWithShadow(font, Text.translatable("baseinsurance.map.volume", compact(volume)), x, 138,
				volume > 16_000_000 ? 0xFFFF5050 : WHITE);
		int loaded = loadedPercent();
		if (editing == null) {
			context.drawTextWithShadow(font, Text.translatable("baseinsurance.map.loaded", loaded), x, 149,
					loaded == 100 ? 0xFF50FF70 : GOLD);
		}

		// Below that: the error if there is one, otherwise what "loaded" means, in whatever room is left.
		boolean showError = error != null && (editing != null || nameField != null && !nameField.getText().isEmpty());
		Text note = showError ? Text.literal(error) : editing != null ? Text.translatable("baseinsurance.map.edit_note")
				: Text.translatable(loaded == 100 ? "baseinsurance.map.loaded_all" : "baseinsurance.map.loaded_some");
		int textY = editing == null ? 162 : 151;
		// Whole sentences only: as many as fit, never one cut off halfway.
		int room = Math.max(0, (height - 52 - textY + 1) / 10);
		String text = note.getString();
		var lines = font.wrapLines(Text.literal(text), PANEL_WIDTH - 10);
		while (lines.size() > room && text.lastIndexOf(". ") > 0) {
			text = text.substring(0, text.lastIndexOf(". ") + 1);
			lines = font.wrapLines(Text.literal(text), PANEL_WIDTH - 10);
		}
		if (lines.size() > room) lines = lines.subList(0, room);
		for (var line : lines) {
			context.drawTextWithShadow(font, line, x, textY, showError ? 0xFFFF5050 : GRAY);
			textY += 10;
		}
	}

	/** How much of the proposed area is loaded right now (and so can be snapshotted straight away). */
	/** How much of the proposed area (by footprint) is within the trust radius of you, so captured straight away. */
	private int loadedPercent() {
		if (client.player == null) return 0;
		double radius = BIConfig.INSTANCE.trustRadius;
		double px = client.player.getX(), pz = client.player.getZ();
		int total = 0, near = 0;
		// Sample every 4 blocks; plenty for a percentage.
		for (int x = selMinX; x <= selMaxX; x += 4) {
			for (int z = selMinZ; z <= selMaxZ; z += 4) {
				total++;
				double dx = x + 0.5 - px, dz = z + 0.5 - pz;
				if (dx * dx + dz * dz <= radius * radius && client.world.isChunkLoaded(x >> 4, z >> 4)) near++;
			}
		}
		return total == 0 ? 0 : 100 * near / total;
	}

	/** A dashed circle showing the trust radius around you: what gets checked from where you stand. */
	private void drawTrustCircle(DrawContext context) {
		if (client.player == null) return;
		double radius = BIConfig.INSTANCE.trustRadius * zoom;
		if (radius < 6) return;
		var pos = client.player.getLerpedPos(client.getRenderTickCounter().getTickProgress(false));
		double cx = screenX(pos.x), cy = screenY(pos.z);
		int dashes = 72;
		for (int i = 0; i < dashes; i += 2) {
			for (int step = 0; step < 4; step++) {
				double angle = (i + step / 4.0) / dashes * Math.PI * 2;
				int x = (int) Math.round(cx + Math.cos(angle) * radius), y = (int) Math.round(cy + Math.sin(angle) * radius);
				context.fill(x, y, x + 1, y + 1, 0xC0FFE060);
			}
		}
	}

	static double testCenterX() {
		return centerX;
	}

	static double testCenterZ() {
		return centerZ;
	}

	static double testZoom() {
		return zoom;
	}

	/** Sets the view; for the dev tests. */
	static void testView(double x, double z, double newZoom) {
		centerX = x;
		centerZ = z;
		zoom = newZoom;
		following = false;
	}

	/** A map centred on a base. */
	public static MapScreen centeredOn(Base base) {
		MapScreen screen = new MapScreen();
		following = false;
		centerX = (base.minX + base.maxX + 1) / 2.0;
		centerZ = (base.minZ + base.maxZ + 1) / 2.0;
		viewDimension = base.dimension;
		return screen;
	}

	private static String compact(long n) {
		if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
		if (n >= 1_000) return String.format(Locale.ROOT, "%.0fk", n / 1_000.0);
		return Long.toString(n);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput key) {
		if (menu != null && key.key() == GLFW.GLFW_KEY_ESCAPE) {
			menu = null;
			return true;
		}
		if (fieldMode != Field.NONE && topField != null) {
			if (key.key() == GLFW.GLFW_KEY_ESCAPE) {
				closeField();
				return true;
			}
			if (key.key() == GLFW.GLFW_KEY_ENTER || key.key() == GLFW.GLFW_KEY_KP_ENTER) {
				submitField();
				return true;
			}
			return super.keyPressed(key);
		}
		if (ruler != null && key.key() == GLFW.GLFW_KEY_ESCAPE) {
			ruler = null;
			return true;
		}
		if (!createMode && key.key() == GLFW.GLFW_KEY_G && (key.modifiers() & GLFW.GLFW_MOD_CONTROL) == 0) {
			openField(Field.GOTO, "");
			return true;
		}
		// The map key closes the map again, unless you're typing a name.
		boolean typing = nameField != null && nameField.isFocused();
		if (!createMode && !typing && BaseInsuranceClient.INSTANCE.isMapKey(key)) {
			close();
			return true;
		}
		return super.keyPressed(key);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
