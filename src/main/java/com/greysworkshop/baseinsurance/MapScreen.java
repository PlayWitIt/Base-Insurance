package com.greysworkshop.baseinsurance;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2fStack;

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
	private enum Drag { NONE, PAN, MOVE, N, S, E, W, NE, NW, SE, SW }

	private static final int PANEL_WIDTH = 168;
	private static final int ACCENT = 0xFF40E0FF;
	private static final int GOLD = 0xFFFFC040;
	private static final int GRAY = 0xFF9090A8;
	private static final int WHITE = 0xFFF0F0F0;
	private static final int PANEL = 0xE0101018;
	private static final int BORDER = 0xFF33334D;
	private static final double MIN_ZOOM = 0.125;
	private static final double MAX_ZOOM = 16;
	private static final int MIN_SIZE = 4;

	// The view is remembered between openings (per session).
	private static double centerX = Double.NaN;
	private static double centerZ;
	private static double zoom = 2;
	private static String viewDimension;

	private final boolean createMode;
	private final String initialName;

	// Create mode: the proposed area and height range.
	private int selMinX, selMaxX, selMinZ, selMaxZ;
	private int baseY;
	private int down = 24;
	private int up = 48;
	private TextFieldWidget nameField;
	private ButtonWidget confirmButton;
	private String error;

	private Drag drag = Drag.NONE;
	private double pressX, pressY;
	private boolean moved;
	private int[] dragStart;
	private final List<int[]> baseListRows = new ArrayList<>();

	public MapScreen() {
		this(false, null);
	}

	private MapScreen(boolean createMode, String name) {
		super(Text.translatable(createMode ? "baseinsurance.map.create_title" : "baseinsurance.map.title"));
		this.createMode = createMode;
		this.initialName = name;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) {
			BlockPos p = client.player.getBlockPos();
			String dimension = BaseManager.dimensionKey(client.world);
			if (createMode || Double.isNaN(centerX) || !dimension.equals(viewDimension)) {
				centerX = p.getX() + 0.5;
				centerZ = p.getZ() + 0.5;
				viewDimension = dimension;
			}
			baseY = p.getY();
			selMinX = p.getX() - 48;
			selMaxX = p.getX() + 48;
			selMinZ = p.getZ() - 48;
			selMaxZ = p.getZ() + 48;
			if (createMode) zoom = Math.max(zoom, 1.5);
		}
	}

	public static MapScreen forCreate(String name) {
		return new MapScreen(true, name);
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

	@Override
	protected void init() {
		if (!createMode) {
			addDrawableChild(ButtonWidget.builder(Text.translatable("baseinsurance.map.center"), b -> centerOnPlayer())
					.dimensions(6, height - 26, 100, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.translatable("baseinsurance.map.new_base"),
					b -> client.setScreen(forCreate(Commands.defaultName())))
					.dimensions(110, height - 26, 100, 20).build());
			addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
					.dimensions(width - 106, height - 26, 100, 20).build());
			return;
		}

		int x = width - PANEL_WIDTH - 2;
		int w = PANEL_WIDTH - 6;
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
		return Math.max(client.world.getBottomY(), baseY - down);
	}

	private int maxY() {
		return Math.min(client.world.getTopYInclusive(), baseY + up);
	}

	private long volume() {
		return (long) (selMaxX - selMinX + 1) * (maxY() - minY() + 1) * (selMaxZ - selMinZ + 1);
	}

	private void updateConfirm() {
		if (confirmButton == null) return;
		error = Commands.validate(nameField.getText(), volume());
		confirmButton.active = error == null;
	}

	/** Creates the base from the drawn area. Public so the self-test can press it. */
	public void confirm() {
		updateConfirm();
		if (error != null) return;
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
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (super.mouseClicked(click, doubled)) return true;
		double mx = click.x(), my = click.y();
		if (mx >= mapRight()) return false;
		pressX = mx;
		pressY = my;
		moved = false;

		// The base list in the corner: click to jump to a base.
		for (int[] row : baseListRows) {
			if (my >= row[1] && my < row[1] + 11 && mx >= 6 && mx < row[2]) {
				Base base = BaseInsuranceClient.INSTANCE.manager.all().get(row[0]);
				centerX = (base.minX + base.maxX + 1) / 2.0;
				centerZ = (base.minZ + base.maxZ + 1) / 2.0;
				return true;
			}
		}

		drag = createMode ? handleAt(mx, my) : Drag.PAN;
		dragStart = new int[]{selMinX, selMaxX, selMinZ, selMaxZ, (int) Math.floor(worldX(mx)), (int) Math.floor(worldZ(my))};
		return true;
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
			case PAN -> {
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
		context.enableScissor(0, 0, right, height);
		drawTiles(context, right);
		drawGrid(context, right);
		for (Base base : bases()) drawBase(context, base);
		if (createMode) drawSelection(context);
		drawTrustCircle(context);
		drawPlayer(context);
		context.disableScissor();
		drawOverlayText(context, mouseX, mouseY, right);
		if (createMode) drawPanel(context);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		if (!createMode && drag == Drag.NONE && mouseX < mapRight()) {
			Base base = baseAt(mouseX, mouseY);
			if (base != null) {
				Text tip = base.damageCount() == 0
						? Text.translatable("baseinsurance.map.tip_intact", base.name)
						: Text.translatable("baseinsurance.map.tip_damage", base.name, Alerts.plural(base.damageCount(), "change"));
				context.drawTooltip(textRenderer, tip, mouseX, mouseY);
			}
		}
	}

	private void drawTiles(DrawContext context, int right) {
		WorldMap map = BaseInsuranceClient.INSTANCE.worldMap;
		if (map.dimension() == null) return;
		int rx0 = Math.floorDiv((int) Math.floor(worldX(0)), WorldMap.TILE);
		int rx1 = Math.floorDiv((int) Math.floor(worldX(right)), WorldMap.TILE);
		int rz0 = Math.floorDiv((int) Math.floor(worldZ(0)), WorldMap.TILE);
		int rz1 = Math.floorDiv((int) Math.floor(worldZ(height)), WorldMap.TILE);
		Matrix3x2fStack matrices = context.getMatrices();
		for (int rx = rx0; rx <= rx1; rx++) {
			for (int rz = rz0; rz <= rz1; rz++) {
				WorldMap.Tile tile = map.tile(rx, rz, false);
				if (tile == null) continue;
				Identifier id = map.texture(tile);
				matrices.pushMatrix();
				matrices.translate((float) screenX(rx * WorldMap.TILE), (float) screenY(rz * WorldMap.TILE));
				matrices.scale((float) zoom, (float) zoom);
				context.drawTexture(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0, 0, WorldMap.TILE, WorldMap.TILE, WorldMap.TILE, WorldMap.TILE);
				matrices.popMatrix();
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

	private void drawPlayer(DrawContext context) {
		if (client.player == null) return;
		float sx = (float) screenX(client.player.getX()), sy = (float) screenY(client.player.getZ());
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
		context.fill(4, 4, 12 + Math.max(font.getWidth(title), font.getWidth(info)), 28, PANEL);
		context.drawTextWithShadow(font, title, 8, 7, GOLD);
		context.drawTextWithShadow(font, info, 8, 17, GRAY);

		// Base list.
		baseListRows.clear();
		if (!createMode) {
			int y = 34;
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
				baseListRows.add(new int[]{i, y, 4 + w});
				y += 12;
			}
		}

		// Help along the bottom, above the buttons, cut to fit.
		String help = Text.translatable(createMode ? "baseinsurance.map.help_create" : "baseinsurance.map.help").getString();
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
		context.drawTextWithShadow(font, Text.translatable("baseinsurance.map.create_title"), x, 8, GOLD);
		context.drawTextWithShadow(font, Text.translatable("baseinsurance.map.name"), x, 31, GRAY);

		int across = selMaxX - selMinX + 1, depth = selMaxZ - selMinZ + 1, tall = maxY() - minY() + 1;
		context.drawTextWithShadow(font, Text.translatable("baseinsurance.map.area", across, depth), x, 64, WHITE);
		context.drawTextWithShadow(font, Text.translatable("baseinsurance.map.height", minY(), maxY(), tall), x, 75, WHITE);

		long volume = volume();
		context.drawTextWithShadow(font, Text.translatable("baseinsurance.map.volume", compact(volume)), x, 138,
				volume > 16_000_000 ? 0xFFFF5050 : WHITE);
		int loaded = loadedPercent();
		context.drawTextWithShadow(font, Text.translatable("baseinsurance.map.loaded", loaded), x, 149,
				loaded == 100 ? 0xFF50FF70 : GOLD);

		// Below that: the error if there is one, otherwise what "loaded" means, in whatever room is left.
		boolean showError = error != null && nameField != null && !nameField.getText().isEmpty();
		Text note = showError ? Text.literal(error)
				: Text.translatable(loaded == 100 ? "baseinsurance.map.loaded_all" : "baseinsurance.map.loaded_some");
		int textY = 162;
		for (var line : font.wrapLines(note, PANEL_WIDTH - 10)) {
			if (textY + 9 > height - 52) break;
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
		double cx = screenX(client.player.getX()), cy = screenY(client.player.getZ());
		int dashes = 72;
		for (int i = 0; i < dashes; i += 2) {
			for (int step = 0; step < 4; step++) {
				double angle = (i + step / 4.0) / dashes * Math.PI * 2;
				int x = (int) Math.round(cx + Math.cos(angle) * radius), y = (int) Math.round(cy + Math.sin(angle) * radius);
				context.fill(x, y, x + 1, y + 1, 0xC0FFE060);
			}
		}
	}

	/** A map centred on a base. */
	public static MapScreen centeredOn(Base base) {
		MapScreen screen = new MapScreen();
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
	public boolean shouldPause() {
		return false;
	}
}
