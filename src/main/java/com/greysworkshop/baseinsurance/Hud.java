package com.greysworkshop.baseinsurance;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** On-screen info: build mode warning, damage left in the base you're in, and what belongs where you're aiming. */
public final class Hud {
	private Hud() {}

	public static void render(DrawContext context, RenderTickCounter tickCounter, BaseManager manager, Scanner scanner) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!BIConfig.INSTANCE.enabled || client.world == null || client.player == null || client.options.hudHidden) return;
		TextRenderer font = client.textRenderer;
		boolean right = BIConfig.INSTANCE.hudCorner == BIConfig.Corner.TOP_RIGHT;
		int y = 4;

		if (scanner.buildMode) {
			drawCorner(context, font, Text.translatable("baseinsurance.hud.build_mode"), y, 0xFFFFD000, right);
			y += 11;
		}

		Base here = null;
		for (Base base : manager.inDimension(BaseManager.dimensionKey(client.world))) {
			if (base.distanceTo(client.player.getX(), client.player.getY(), client.player.getZ()) <= 16) {
				here = base;
				break;
			}
		}
		if (BIConfig.INSTANCE.hudStatus && here != null) {
			int count = here.changes.size();
			int percent = here.snapshotPercent();
			if (count > 0 || Overlay.enabled) {
				Text status = count == 0
						? Text.translatable("baseinsurance.hud.intact", here.name)
						: Text.translatable("baseinsurance.hud.damage", here.name, count);
				drawCorner(context, font, status, y, count == 0 ? 0xFF55FF55 : 0xFFFF5555, right);
				y += 11;
			}
			if (percent < 100) {
				// Until the whole base has been seen up close, say so, with a little progress bar.
				Text progress = Text.translatable("baseinsurance.hud.snapshot", here.name, percent);
				drawCorner(context, font, progress, y, 0xFFFFD040, right);
				int barWidth = font.getWidth(progress);
				int barX = right ? context.getScaledWindowWidth() - 4 - barWidth : 4;
				context.fill(barX, y + 10, barX + barWidth, y + 12, 0x60000000);
				context.fill(barX, y + 10, barX + barWidth * percent / 100, y + 12, 0xFFFFD040);
			}
		}

		if (Overlay.enabled && BIConfig.INSTANCE.aimInfo && client.currentScreen == null) drawAimInfo(context, font, client, manager);
	}

	private static void drawCorner(DrawContext context, TextRenderer font, Text text, int y, int color, boolean right) {
		int x = right ? context.getScaledWindowWidth() - 4 - font.getWidth(text) : 4;
		context.drawTextWithShadow(font, text, x, y, color);
	}

	/** Walks along your line of sight to the first changed block, stopping at anything solid that isn't one. */
	private static void drawAimInfo(DrawContext context, TextRenderer font, MinecraftClient client, BaseManager manager) {
		Vec3d eye = client.player.getEyePos();
		Vec3d look = client.player.getRotationVec(1.0f);
		double reach = Math.min(48, BIConfig.INSTANCE.overlayRange);
		long last = Long.MIN_VALUE;
		Base.Change hit = null;
		var bases = manager.inDimension(BaseManager.dimensionKey(client.world));
		for (double t = 0; t <= reach && hit == null; t += 0.1) {
			BlockPos pos = BlockPos.ofFloored(eye.add(look.multiply(t)));
			long key = pos.asLong();
			if (key == last) continue;
			last = key;
			for (Base base : bases) {
				if (base.contains(pos)) {
					hit = base.changes.get(key);
					if (hit != null && !Overlay.shown(hit.kind())) hit = null;
					if (hit != null) break;
				}
			}
			if (hit == null && !client.world.getBlockState(pos).isAir()) break;
		}
		if (hit == null) return;

		Text line = switch (hit.kind()) {
			case REMOVED -> Text.translatable("baseinsurance.hud.missing", BlockStates.name(hit.was));
			case ADDED -> Text.translatable("baseinsurance.hud.added", BlockStates.name(hit.now));
			case REPLACED -> Text.translatable("baseinsurance.hud.replaced", BlockStates.name(hit.was), BlockStates.name(hit.now));
		};
		BlockState iconState = hit.kind() == Base.Kind.ADDED ? hit.nowState() : hit.wasState();
		ItemStack icon = BlockStates.icon(iconState);

		int width = font.getWidth(line) + (icon.isEmpty() ? 0 : 18);
		int x = context.getScaledWindowWidth() / 2 - width / 2;
		int y = context.getScaledWindowHeight() / 2 - 30;
		if (!icon.isEmpty()) {
			context.drawItem(icon, x, y - 4);
			x += 18;
		}
		int color = 0xFF000000 | Overlay.color(hit.kind());
		context.drawTextWithShadow(font, line, x, y, color);
		String when = (hit.live ? "Live, " : "While away, ") + "found " + Alerts.ago(hit.firstSeen);
		context.drawTextWithShadow(font, when, context.getScaledWindowWidth() / 2 - font.getWidth(when) / 2, y + 11,
				0xFFB0B0B0);
	}
}
