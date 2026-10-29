package com.greysworkshop.baseinsurance;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.List;

/** How to use Base Insurance, in four steps, with buttons to get going. Opened with /bi guide. */
public class GuideScreen extends Screen {
	private static final int GOLD = 0xFFFFC040;
	private static final int WHITE = 0xFFF0F0F0;
	private static final int GRAY = 0xFFA0A0B8;
	private static final int CYAN = 0xFF40E0FF;

	private record Step(ItemStack icon, String title, String body) {}

	private static final List<Step> STEPS = List.of(
			new Step(new ItemStack(Items.FILLED_MAP), "1. Insure your base",
					"Click \"Insure a base\" below (or type /bi create) and drag the area on the map to cover your base. "
							+ "Resize it any time from its report."),
			new Step(new ItemStack(Items.COMPASS), "2. Walk around it once",
					"Everything within 48 blocks of you is checked, out of sight too. The top-left corner shows how much of "
							+ "the base has been checked and points to the parts still to do."),
			new Step(new ItemStack(Items.CHEST), "3. Open your chests",
					"Open each chest, barrel and shulker box once so its contents are on record. Then anything taken is reported."),
			new Step(new ItemStack(Items.BELL), "4. Get told what changed",
					"Alerts tell you what changed while you were away or while you're there, and who was nearby. "
							+ "Only real changes: what servers fake (hidden ores, hidden chests) never counts. The report shows it "
							+ "all: Show me marks it through walls, It's fine stops reporting it, Never count ignores that kind of "
							+ "block. Everything can be undone. M cycles the map, radar and minimap.")
	);

	private final Screen parent;
	private double scroll;
	private int contentHeight;

	public GuideScreen(Screen parent) {
		super(Text.literal("Base Insurance: getting started"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int w = Math.min(120, (width - 40) / 3);
		int x = width / 2 - (3 * w + 8) / 2;
		int y = height - 28;
		addDrawableChild(ButtonWidget.builder(Text.literal("Insure a base"), b -> client.setScreen(MapScreen.forCreate(Commands.defaultName())))
				.dimensions(x, y, w, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Settings"), b -> client.setScreen(new SettingsScreen(this)))
				.dimensions(x + w + 4, y, w, 20).build());
		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close()).dimensions(x + 2 * (w + 4), y, w, 20).build());
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		super.renderBackground(context, mouseX, mouseY, delta);
		int panelWidth = Math.min(width - 24, 420);
		int left = width / 2 - panelWidth / 2;
		context.fill(left - 8, 8, left + panelWidth + 8, height - 34, 0xD0101018);
		context.drawStrokedRectangle(left - 8, 8, panelWidth + 16, height - 42, 0xFF33334D);
		context.drawTextWithShadow(textRenderer, Text.literal("⚑ ").styled(s -> s.withColor(CYAN)).append(title), left, 16, GOLD);

		int top = 30, bottom = height - 36;
		context.enableScissor(left - 8, top, left + panelWidth + 8, bottom);
		int y = top + 2 - (int) scroll;
		int start = y;
		for (Step step : STEPS) {
			context.drawItem(step.icon(), left, y);
			context.drawTextWithShadow(textRenderer, step.title(), left + 22, y + 4, WHITE);
			y += 16;
			for (OrderedText line : textRenderer.wrapLines(Text.literal(step.body()), panelWidth - 22)) {
				context.drawTextWithShadow(textRenderer, line, left + 22, y, GRAY);
				y += 10;
			}
			y += 6;
		}
		String keys = "Keys: M goes map, trail radar (with Naughty or Nice), minimap, off; - and = zoom the minimap. Set keys for the report, overlay and build mode in Options → Controls → Base Insurance. "
				+ "Everything stays on your computer; /bi commands are never sent to the server.";
		for (OrderedText line : textRenderer.wrapLines(Text.literal(keys), panelWidth)) {
			context.drawTextWithShadow(textRenderer, line, left, y, CYAN);
			y += 10;
		}
		context.disableScissor();
		contentHeight = y - start;
		double max = maxScroll();
		if (max > 0) {
			int view = bottom - top;
			int bar = Math.max(16, (int) (view * view / (view + max)));
			int barY = top + (int) ((view - bar) * scroll / max);
			context.fill(left + panelWidth + 4, top, left + panelWidth + 6, bottom, 0x30FFFFFF);
			context.fill(left + panelWidth + 4, barY, left + panelWidth + 6, barY + bar, CYAN);
		}
	}

	private double maxScroll() {
		return Math.max(0, contentHeight - (height - 36 - 30) + 4);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll = Math.max(0, Math.min(maxScroll(), scroll - verticalAmount * 20));
		return true;
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}
}
