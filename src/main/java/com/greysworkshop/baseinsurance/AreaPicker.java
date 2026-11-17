package com.greysworkshop.baseinsurance;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Drawing an area of any shape on the map, for another mod (Groundskeeper's big areas). Rectangles, circles (or
 * ellipses with Shift), polygons and brush strokes, each adding to the area or cutting out of it; undo; the area's
 * size as you go, and the tiles Groundskeeper has already worked. Done hands the shapes back as JSON.
 * <p>
 * Other mods open it by reflection: {@code AreaPicker.open(String title, String shapesJson, Consumer<String> done)};
 * {@code done} gets the new shapes, or null if it's cancelled.
 */
public final class AreaPicker extends Screen {
	enum Tool {
		RECT("Rect", "Drag from corner to corner"),
		CIRCLE("Circle", "Drag out from the middle · Shift: ellipse"),
		POLYGON("Shape", "Click each corner · click the first (or Enter) to finish · Backspace: last corner off"),
		BRUSH("Brush", "Drag to paint · [ and ] change the size");

		final String label, help;

		Tool(String label, String help) {
			this.label = label;
			this.help = help;
		}
	}

	private static final int ACCENT = 0xFF40E0FF, CUT = 0xFFFF5A5A, GOLD = 0xFFFFC040, GRAY = 0xFF9090A8, WHITE = 0xFFF0F0F0, PANEL = 0xE0101018;
	private static final double MIN_ZOOM = MapScreen.MIN_ZOOM, MAX_ZOOM = 16;

	private final String heading;
	private final Consumer<String> done;
	final List<AreaShapes.Shape> shapes;
	Tool tool = Tool.RECT;
	boolean cutting;
	int brush = 8;
	private double centerX, centerZ, zoom = 1;
	private boolean fitted;

	// The shape being drawn: the press point (blocks), and for polygons and brushes the points so far.
	private int[] press;
	private int[] drawTo;
	private boolean shift;
	final List<Integer> points = new ArrayList<>();
	private boolean drawing;
	private boolean panning;
	private double lastX, lastY;
	private long columns = -1;
	private String problem;
	private boolean finished;
	private final List<ButtonWidget> toolButtons = new ArrayList<>();
	private ButtonWidget modeButton;

	private AreaPicker(String heading, String json, Consumer<String> done) {
		super(Text.literal(heading));
		this.heading = heading;
		this.done = done;
		this.shapes = AreaShapes.fromJson(json);
		GroundskeeperArea.refresh();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) {
			centerX = client.player.getX();
			centerZ = client.player.getZ();
		}
	}

	/** Opens the picker. Called by other mods (by reflection), from any thread. */
	public static void open(String title, String shapesJson, Consumer<String> done) {
		MinecraftClient client = MinecraftClient.getInstance();
		client.execute(() -> client.setScreen(new AreaPicker(title == null || title.isBlank() ? "Area" : title, shapesJson, done)));
	}

	/** For the self-test. */
	static AreaPicker create(String title, String shapesJson, Consumer<String> done) {
		return new AreaPicker(title, shapesJson, done);
	}

	// ---- view ----

	private int mapBottom() {
		return height - 30;
	}

	double screenX(double wx) {
		return width / 2.0 + (wx - centerX) * zoom;
	}

	double screenY(double wz) {
		return mapBottom() / 2.0 + (wz - centerZ) * zoom;
	}

	private double worldX(double sx) {
		return centerX + (sx - width / 2.0) / zoom;
	}

	private double worldZ(double sy) {
		return centerZ + (sy - mapBottom() / 2.0) / zoom;
	}

	private int blockX(double sx) {
		return (int) Math.floor(worldX(sx));
	}

	private int blockZ(double sy) {
		return (int) Math.floor(worldZ(sy));
	}

	/** The whole area on screen, or (with none yet) a few hundred blocks round you. */
	private void fit() {
		int[] b = AreaShapes.bounds(shapes);
		if (b == null) {
			zoom = 1;
			return;
		}
		centerX = (b[0] + b[2] + 1) / 2.0;
		centerZ = (b[1] + b[3] + 1) / 2.0;
		zoom = MathHelper.clamp(Math.min((width - 40) / (double) (b[2] - b[0] + 1), (mapBottom() - 60) / (double) (b[3] - b[1] + 1)), MIN_ZOOM, MAX_ZOOM);
	}

	// ---- widgets ----

	@Override
	protected void init() {
		if (!fitted) {
			fitted = true;
			fit();
		}
		toolButtons.clear();
		int n = 9, gap = 3;
		int bw = Math.max(30, Math.min(70, (width - 12 - gap * (n - 1)) / n));
		int x = 6, y = height - 25;
		for (Tool t : Tool.values()) {
			ButtonWidget b = addDrawableChild(ButtonWidget.builder(Text.literal(t.label), w -> pick(t)).tooltip(Tooltip.of(Text.literal(t.label + ": " + t.help)))
					.dimensions(x, y, bw, 20).build());
			toolButtons.add(b);
			x += bw + gap;
		}
		modeButton = addDrawableChild(ButtonWidget.builder(Text.empty(), w -> setCutting(!cutting))
				.tooltip(Tooltip.of(Text.literal("Add to the area, or cut out of it (a base, a farm) · X"))).dimensions(x, y, bw, 20).build());
		x += bw + gap;
		addDrawableChild(ButtonWidget.builder(Text.literal("Undo"), w -> undo()).tooltip(Tooltip.of(Text.literal("Take the last shape off · Ctrl+Z")))
				.dimensions(x, y, bw, 20).build());
		x += bw + gap;
		addDrawableChild(ButtonWidget.builder(Text.literal("Clear"), w -> clearAll()).tooltip(Tooltip.of(Text.literal("Start again with nothing")))
				.dimensions(x, y, bw, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), w -> finish(true)).tooltip(Tooltip.of(Text.literal("Use this area · Enter")))
				.dimensions(width - 6 - bw, y, bw, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), w -> finish(false)).tooltip(Tooltip.of(Text.literal("Leave the area as it was · Esc")))
				.dimensions(width - 6 - 2 * bw - gap, y, bw, 20).build());
		refreshButtons();
	}

	private void refreshButtons() {
		for (int i = 0; i < toolButtons.size(); i++) {
			Tool t = Tool.values()[i];
			toolButtons.get(i).setMessage(t == tool ? Text.literal(t.label).formatted(Formatting.YELLOW, Formatting.UNDERLINE) : Text.literal(t.label));
		}
		if (modeButton != null) modeButton.setMessage(cutting ? Text.literal("Cut").formatted(Formatting.RED) : Text.literal("Add").formatted(Formatting.AQUA));
	}

	void pick(Tool t) {
		tool = t;
		cancelDrawing();
		refreshButtons();
	}

	void setCutting(boolean cut) {
		cutting = cut;
		refreshButtons();
	}

	void undo() {
		if (!points.isEmpty() && tool == Tool.POLYGON) {
			cancelDrawing();
			return;
		}
		if (!shapes.isEmpty()) {
			shapes.remove(shapes.size() - 1);
			changed();
		}
	}

	void clearAll() {
		cancelDrawing();
		shapes.clear();
		changed();
	}

	private void cancelDrawing() {
		drawing = false;
		press = drawTo = null;
		points.clear();
	}

	void add(AreaShapes.Shape s) {
		if (s == null || !s.valid()) return;
		shapes.add(s.op(cutting));
		changed();
	}

	private void changed() {
		columns = -1;
		problem = AreaShapes.tooBig(shapes);
	}

	long columns() {
		if (columns < 0) columns = problem != null ? 0 : AreaShapes.columns(shapes);
		return columns;
	}

	/** Done (hands the shapes back) or Cancel. */
	void finish(boolean ok) {
		if (finished) return;
		if (ok && problem != null) return;
		finished = true;
		String json = ok ? AreaShapes.toJson(shapes) : null;
		client.setScreen(null);
		try {
			done.accept(json);
		} catch (RuntimeException e) {
			BaseInsuranceClient.LOGGER.warn("The mod that asked for an area didn't take it", e);
		}
	}

	@Override
	public void close() {
		finish(false);
	}

	// ---- input ----

	/** The shape the current drag makes (or null). */
	private AreaShapes.Shape pending() {
		if (press == null || drawTo == null) return null;
		return switch (tool) {
			case RECT -> AreaShapes.Shape.rect(press[0], press[1], drawTo[0], drawTo[1]);
			case CIRCLE -> {
				int dx = Math.abs(drawTo[0] - press[0]), dz = Math.abs(drawTo[1] - press[1]);
				if (shift) yield AreaShapes.Shape.ellipse(press[0], press[1], Math.max(1, dx), Math.max(1, dz));
				int r = Math.max(1, (int) Math.round(Math.hypot(dx, dz)));
				yield AreaShapes.Shape.ellipse(press[0], press[1], r, r);
			}
			default -> null;
		};
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (ModsPanel.streamSafe()) return true;
		if (super.mouseClicked(click, doubled)) return true;
		double mx = click.x(), my = click.y();
		if (my >= mapBottom()) return false;
		lastX = mx;
		lastY = my;
		// Right or middle button: move the map about.
		if (click.button() != 0) {
			panning = true;
			return true;
		}
		int bx = blockX(mx), bz = blockZ(my);
		shift = click.hasShift();
		switch (tool) {
			case RECT, CIRCLE -> {
				press = new int[] {bx, bz};
				drawTo = new int[] {bx, bz};
				drawing = true;
			}
			case POLYGON -> polygonClick(mx, my, bx, bz, doubled);
			case BRUSH -> {
				points.clear();
				AreaShapes.stroke(points, brush, bx, bz);
				drawing = true;
			}
		}
		return true;
	}

	void polygonClick(double mx, double my, int bx, int bz, boolean doubled) {
		if (points.size() >= 6) {
			double fx = screenX(points.get(0) + 0.5), fz = screenY(points.get(1) + 0.5);
			if (doubled || Math.hypot(mx - fx, my - fz) <= 6) {
				closePolygon();
				return;
			}
		}
		int n = points.size();
		if (n >= 2 && points.get(n - 2) == bx && points.get(n - 1) == bz) return;
		points.add(bx);
		points.add(bz);
	}

	void closePolygon() {
		if (points.size() >= 6) add(AreaShapes.Shape.polygon(points.stream().mapToInt(Integer::intValue).toArray()));
		points.clear();
	}

	@Override
	public boolean mouseDragged(Click click, double dx, double dy) {
		if (panning) {
			centerX -= (click.x() - lastX) / zoom;
			centerZ -= (click.y() - lastY) / zoom;
			lastX = click.x();
			lastY = click.y();
			return true;
		}
		if (!drawing) return super.mouseDragged(click, dx, dy);
		int bx = blockX(click.x()), bz = blockZ(click.y());
		shift = click.hasShift();
		if (tool == Tool.BRUSH) AreaShapes.stroke(points, brush, bx, bz);
		else drawTo = new int[] {bx, bz};
		return true;
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (panning) {
			panning = false;
			return true;
		}
		if (drawing) {
			drawing = false;
			if (tool == Tool.BRUSH) {
				if (!points.isEmpty()) add(AreaShapes.Shape.brush(brush, points.stream().mapToInt(Integer::intValue).toArray()));
				points.clear();
			} else {
				AreaShapes.Shape s = pending();
				// A click without a drag makes nothing (a 1-block rectangle is rarely meant).
				if (s != null && !(press[0] == drawTo[0] && press[1] == drawTo[1])) add(s);
				press = drawTo = null;
			}
			return true;
		}
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		double beforeX = worldX(mouseX), beforeZ = worldZ(mouseY);
		zoom = MathHelper.clamp(zoom * Math.pow(1.25, verticalAmount), MIN_ZOOM, MAX_ZOOM);
		centerX = beforeX - (mouseX - width / 2.0) / zoom;
		centerZ = beforeZ - (mouseY - mapBottom() / 2.0) / zoom;
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput key) {
		int k = key.key();
		boolean ctrl = (key.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
		if (k == GLFW.GLFW_KEY_ESCAPE) {
			if (!points.isEmpty() || drawing) cancelDrawing();
			else finish(false);
			return true;
		}
		if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
			if (tool == Tool.POLYGON && !points.isEmpty()) closePolygon();
			else finish(true);
			return true;
		}
		if (ctrl && k == GLFW.GLFW_KEY_Z) {
			undo();
			return true;
		}
		if (k == GLFW.GLFW_KEY_BACKSPACE && tool == Tool.POLYGON && points.size() >= 2) {
			points.remove(points.size() - 1);
			points.remove(points.size() - 1);
			return true;
		}
		if (!ctrl) {
			switch (k) {
				case GLFW.GLFW_KEY_R -> pick(Tool.RECT);
				case GLFW.GLFW_KEY_C -> pick(Tool.CIRCLE);
				case GLFW.GLFW_KEY_P -> pick(Tool.POLYGON);
				case GLFW.GLFW_KEY_B -> pick(Tool.BRUSH);
				case GLFW.GLFW_KEY_X -> setCutting(!cutting);
				case GLFW.GLFW_KEY_LEFT_BRACKET -> brush = Math.max(1, brush - (brush > 16 ? 4 : 1));
				case GLFW.GLFW_KEY_RIGHT_BRACKET -> brush = Math.min(256, brush + (brush >= 16 ? 4 : 1));
				default -> {
					return super.keyPressed(key);
				}
			}
			return true;
		}
		return super.keyPressed(key);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	// ---- drawing ----

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, width, height, 0xFF0B0B12);
		int bottom = mapBottom();
		context.enableScissor(0, 0, width, bottom);
		MapRender.drawTerrain(context, width / 2.0, bottom / 2.0, centerX, centerZ, zoom, width / 2.0, bottom / 2.0);
		drawTiles(context, bottom);
		drawArea(context, bottom);
		for (AreaShapes.Shape s : shapes) outline(context, s, s.cut() ? 0xB0FF5A5A : 0x9040E0FF);
		AreaShapes.Shape p = pending();
		if (p != null) {
			List<AreaShapes.Shape> one = List.of(p);
			fillShapes(context, one, bottom, cutting ? 0x50FF5A5A : 0x5040E0FF);
			outline(context, p, cutting ? CUT : ACCENT);
		}
		if (tool == Tool.BRUSH && drawing && points.size() >= 2) {
			fillShapes(context, List.of(AreaShapes.Shape.brush(brush, points.stream().mapToInt(Integer::intValue).toArray())), bottom, cutting ? 0x50FF5A5A : 0x5040E0FF);
		}
		if (tool == Tool.POLYGON && !points.isEmpty()) drawPolygonInProgress(context, mouseX, mouseY);
		if (tool == Tool.BRUSH && mouseY < bottom) ring(context, mouseX, mouseY, brush * zoom, cutting ? CUT : ACCENT);
		drawPlayer(context);
		context.disableScissor();
		drawInfo(context, mouseX, mouseY);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		if (ModsPanel.streamSafe()) {
			context.fill(0, 0, width, height, 0xF0101418);
			context.drawCenteredTextWithShadow(textRenderer, "Hidden while stream-safe mode is on", width / 2, height / 2 - 4, 0xFFFFFFFF);
		}
	}

	/** Groundskeeper's tiles, coloured by how far each has got. */
	private void drawTiles(DrawContext context, int bottom) {
		GroundskeeperArea.Area a = GroundskeeperArea.get(client);
		if (a == null) return;
		int span = a.span();
		for (GroundskeeperArea.Tile t : a.tiles()) {
			int x0 = (int) screenX((long) t.tx() * span), x1 = (int) screenX((long) (t.tx() + 1) * span);
			int y0 = (int) screenY((long) t.tz() * span), y1 = (int) screenY((long) (t.tz() + 1) * span);
			if (x1 < 0 || x0 > width || y1 < 0 || y0 > bottom) continue;
			if (t.state() != GroundskeeperArea.State.QUEUED) context.fill(x0, y0, x1, y1, GroundskeeperArea.fill(t.state()));
			if (x1 - x0 >= 6) context.drawStrokedRectangle(x0, y0, x1 - x0, y1 - y0, GroundskeeperArea.outline(t.state()));
		}
	}

	/** The area as it stands: every shape applied in order. */
	private void drawArea(DrawContext context, int bottom) {
		fillShapes(context, shapes, bottom, 0x3840E0FF);
	}

	/** Fills the columns {@code list} makes, a row at a time (a block row each when zoomed in, a pixel row when out). */
	private void fillShapes(DrawContext context, List<AreaShapes.Shape> list, int bottom, int color) {
		if (list.isEmpty()) return;
		int[] b = AreaShapes.bounds(list);
		// Only cuts: nothing is in (their outlines still show).
		if (b == null) return;
		if (zoom >= 1) {
			int z0 = Math.max(b[1], blockZ(0)), z1 = Math.min(b[3], blockZ(bottom));
			for (int z = z0; z <= z1; z++) {
				int sy0 = (int) Math.floor(screenY(z)), sy1 = (int) Math.floor(screenY(z + 1));
				for (int[] r : AreaShapes.row(list, z)) context.fill((int) Math.floor(screenX(r[0])), sy0, (int) Math.floor(screenX(r[1] + 1)), sy1, color);
			}
		} else {
			int sy0 = Math.max(0, (int) screenY(b[1])), sy1 = Math.min(bottom, (int) screenY(b[3] + 1) + 1);
			for (int sy = sy0; sy < sy1; sy++) {
				int z = blockZ(sy + 0.5);
				for (int[] r : AreaShapes.row(list, z)) {
					int x0 = (int) Math.floor(screenX(r[0])), x1 = Math.max(x0 + 1, (int) Math.floor(screenX(r[1] + 1)));
					context.fill(x0, sy, x1, sy + 1, color);
				}
			}
		}
	}

	private void outline(DrawContext context, AreaShapes.Shape s, int color) {
		switch (s.type) {
			case "rect" -> {
				int x0 = (int) screenX(s.x1), x1 = (int) screenX(s.x2 + 1), y0 = (int) screenY(s.z1), y1 = (int) screenY(s.z2 + 1);
				context.drawStrokedRectangle(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0), color);
			}
			case "ellipse" -> {
				double cx = screenX(s.cx + 0.5), cy = screenY(s.cz + 0.5);
				double rx = Math.sqrt((double) s.rx * s.rx + s.rx) * zoom, rz = Math.sqrt((double) s.rz * s.rz + s.rz) * zoom;
				int steps = (int) MathHelper.clamp(Math.max(rx, rz) * 4, 24, 720);
				for (int i = 0; i < steps; i++) {
					double t = i * Math.PI * 2 / steps;
					int x = (int) Math.round(cx + Math.cos(t) * rx), y = (int) Math.round(cy + Math.sin(t) * rz);
					context.fill(x, y, x + 1, y + 1, color);
				}
			}
			case "polygon" -> {
				int n = s.points.length / 2;
				for (int i = 0; i < n; i++) {
					int j = (i + 1) % n;
					line(context, screenX(s.points[2 * i] + 0.5), screenY(s.points[2 * i + 1] + 0.5), screenX(s.points[2 * j] + 0.5), screenY(s.points[2 * j + 1] + 0.5), color);
				}
			}
			default -> {}
		}
	}

	private void drawPolygonInProgress(DrawContext context, int mouseX, int mouseY) {
		int color = cutting ? CUT : ACCENT;
		int n = points.size() / 2;
		for (int i = 0; i + 1 < n; i++) {
			line(context, screenX(points.get(2 * i) + 0.5), screenY(points.get(2 * i + 1) + 0.5), screenX(points.get(2 * i + 2) + 0.5), screenY(points.get(2 * i + 3) + 0.5), color);
		}
		double lx = screenX(points.get(2 * n - 2) + 0.5), ly = screenY(points.get(2 * n - 1) + 0.5);
		line(context, lx, ly, mouseX, mouseY, 0x80FFFFFF);
		for (int i = 0; i < n; i++) {
			int x = (int) screenX(points.get(2 * i) + 0.5), y = (int) screenY(points.get(2 * i + 1) + 0.5);
			int size = i == 0 && n >= 3 ? 4 : 2;
			context.fill(x - size, y - size, x + size + 1, y + size + 1, 0xFF000000);
			context.fill(x - size + 1, y - size + 1, x + size, y + size, i == 0 ? GOLD : color);
		}
	}

	private static void line(DrawContext context, double x0, double y0, double x1, double y1, int color) {
		double len = Math.hypot(x1 - x0, y1 - y0);
		int steps = (int) Math.min(4000, Math.max(1, len));
		for (int i = 0; i <= steps; i++) {
			int x = (int) Math.round(x0 + (x1 - x0) * i / steps), y = (int) Math.round(y0 + (y1 - y0) * i / steps);
			context.fill(x, y, x + 1, y + 1, color);
		}
	}

	private static void ring(DrawContext context, double cx, double cy, double r, int color) {
		if (r < 2) return;
		int steps = (int) MathHelper.clamp(r * 4, 16, 400);
		for (int i = 0; i < steps; i++) {
			double t = i * Math.PI * 2 / steps;
			int x = (int) Math.round(cx + Math.cos(t) * r), y = (int) Math.round(cy + Math.sin(t) * r);
			context.fill(x, y, x + 1, y + 1, color);
		}
	}

	private void drawPlayer(DrawContext context) {
		if (client.player == null) return;
		int sx = (int) screenX(client.player.getX()), sy = (int) screenY(client.player.getZ());
		context.fill(sx - 2, sy - 2, sx + 3, sy + 3, 0xFF000000);
		context.fill(sx - 1, sy - 1, sx + 2, sy + 2, 0xFFFFFFFF);
	}

	private void drawInfo(DrawContext context, int mouseX, int mouseY) {
		TextRenderer font = textRenderer;
		List<String> lines = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		long cols = columns();
		String size = problem != null ? problem : cols == 0 ? "Nothing drawn yet" : String.format(Locale.ROOT, "%,d blocks² (%s)", cols, side(cols));
		lines.add(heading + "  ·  " + size);
		colors.add(problem != null ? CUT : GOLD);
		AreaShapes.Shape p = pending();
		String now;
		if (p != null && "rect".equals(p.type)) now = (p.x2 - p.x1 + 1) + " × " + (p.z2 - p.z1 + 1);
		else if (p != null) now = p.rx == p.rz ? "radius " + p.rx : p.rx + " × " + p.rz + " radii";
		else if (tool == Tool.BRUSH) now = "brush " + brush;
		else if (tool == Tool.POLYGON && !points.isEmpty()) now = points.size() / 2 + " corners";
		else now = null;
		String where = mouseY < mapBottom() ? String.format(Locale.ROOT, "X %d  Z %d", blockX(mouseX), blockZ(mouseY)) : "";
		lines.add((cutting ? "Cutting out · " : "") + tool.label + (now == null ? "" : " " + now) + (where.isEmpty() ? "" : "  ·  " + where));
		colors.add(cutting ? CUT : WHITE);
		lines.add(tool.help);
		colors.add(GRAY);
		lines.add("Right-drag moves the map · scroll zooms · R C P B tools · X add/cut · Ctrl+Z undo");
		colors.add(GRAY);
		GroundskeeperArea.Area a = GroundskeeperArea.get(client);
		if (a != null && !a.tiles().isEmpty()) {
			GroundskeeperArea.Tile t = mouseY < mapBottom() ? a.at(blockX(mouseX), blockZ(mouseY)) : null;
			lines.add(t != null ? "Tile " + t.tx() + "," + t.tz() + ": " + GroundskeeperArea.describe(t)
					: String.format(Locale.ROOT, "Tiles: %d done, %d spreading, %d to do", a.count(GroundskeeperArea.State.DONE), a.count(GroundskeeperArea.State.WORKED),
							a.count(GroundskeeperArea.State.QUEUED) + a.count(GroundskeeperArea.State.SKIPPED)));
			colors.add(0xFFC49BE8);
		}
		int w = 0;
		for (int i = 0; i < lines.size(); i++) {
			lines.set(i, font.trimToWidth(lines.get(i), width - 16));
			w = Math.max(w, font.getWidth(lines.get(i)));
		}
		context.fill(4, 4, 12 + w, 8 + lines.size() * 10, PANEL);
		for (int i = 0; i < lines.size(); i++) context.drawTextWithShadow(font, lines.get(i), 8, 7 + i * 10, colors.get(i));
	}

	static String side(long columns) {
		double s = Math.sqrt(columns);
		return s >= 1000 ? String.format(Locale.ROOT, "like %.1f km square", s / 1000) : String.format(Locale.ROOT, "like %d × %d", Math.round(s), Math.round(s));
	}

	// ---- self-test ----

	/** Drags with the current tool from block (x0, z0) to (x1, z1), as the mouse would. */
	void testDrag(int x0, int z0, int x1, int z1) {
		mouseClicked(new Click(screenX(x0 + 0.5), screenY(z0 + 0.5), new net.minecraft.client.input.MouseInput(0, 0)), false);
		mouseDragged(new Click(screenX(x1 + 0.5), screenY(z1 + 0.5), new net.minecraft.client.input.MouseInput(0, 0)), 1, 1);
		mouseReleased(new Click(screenX(x1 + 0.5), screenY(z1 + 0.5), new net.minecraft.client.input.MouseInput(0, 0)));
	}

	void testView(double x, double z, double newZoom) {
		centerX = x;
		centerZ = z;
		zoom = newZoom;
		fitted = true;
	}
}
