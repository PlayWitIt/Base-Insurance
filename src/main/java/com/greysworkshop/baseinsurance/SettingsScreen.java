package com.greysworkshop.baseinsurance;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

public class SettingsScreen extends Screen {
	private static final int BUTTON_WIDTH = 150;
	private static final int ROW_HEIGHT = 24;

	private final Screen parent;
	private int row;
	private int column;

	public SettingsScreen(Screen parent) {
		super(Text.translatable("baseinsurance.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		BIConfig c = BIConfig.INSTANCE;
		row = 0;
		column = 0;

		toggle("enabled", () -> c.enabled, v -> c.enabled = v);
		toggle("sounds", () -> c.sounds, v -> c.sounds = v);
		toggle("live_alerts", () -> c.liveAlerts, v -> c.liveAlerts = v);
		toggle("player_alerts", () -> c.playerAlerts, v -> c.playerAlerts = v);
		toggle("all_clear", () -> c.allClearMessages, v -> c.allClearMessages = v);
		toggle("auto_overlay", () -> c.autoOverlay, v -> c.autoOverlay = v);
		toggle("overlay_fill", () -> c.overlayFill, v -> c.overlayFill = v);
		slider("overlay_range", 16, 128, () -> c.overlayRange, v -> c.overlayRange = v);
		slider("player_distance", 0, 128, () -> c.playerAlertDistance, v -> c.playerAlertDistance = v);

		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
				.dimensions(width / 2 - 100, height - 28, 200, 20).build());
	}

	private int nextX() {
		return width / 2 + (column == 0 ? -BUTTON_WIDTH - 5 : 5);
	}

	private int nextY() {
		return 36 + row * ROW_HEIGHT;
	}

	private void advance() {
		if (++column == 2) {
			column = 0;
			row++;
		}
	}

	private void toggle(String key, BooleanSupplier getter, Consumer<Boolean> setter) {
		Text label = Text.translatable("baseinsurance.config." + key);
		addDrawableChild(ButtonWidget.builder(ScreenTexts.composeToggleText(label, getter.getAsBoolean()), b -> {
			setter.accept(!getter.getAsBoolean());
			b.setMessage(ScreenTexts.composeToggleText(label, getter.getAsBoolean()));
		}).dimensions(nextX(), nextY(), BUTTON_WIDTH, 20).build());
		advance();
	}

	private void slider(String key, int min, int max, IntSupplier getter, IntConsumer setter) {
		Text label = Text.translatable("baseinsurance.config." + key);
		double start = (getter.getAsInt() - min) / (double) (max - min);
		addDrawableChild(new SliderWidget(nextX(), nextY(), BUTTON_WIDTH, 20, Text.empty(), start) {
			{
				updateMessage();
			}

			private int current() {
				return min + (int) Math.round(MathHelper.clamp(value, 0, 1) * (max - min));
			}

			@Override
			protected void updateMessage() {
				setMessage(Text.empty().append(label).append(": " + current()));
			}

			@Override
			protected void applyValue() {
				setter.accept(current());
			}
		});
		advance();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 15, 0xFFFFFFFF);
	}

	@Override
	public void close() {
		BIConfig.save();
		client.setScreen(parent);
	}
}
