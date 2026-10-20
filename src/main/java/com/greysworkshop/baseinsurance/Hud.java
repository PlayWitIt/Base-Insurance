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
		int y = 4;

		if (scanner.buildMode) {
			context.drawTextWithShadow(font, Text.translatable("baseinsurance.hud.build_mode"), 4, y, 0xFFFFD000);
			y += 11;
		}

		Base here = null;
		for (Base base : manager.inDimension(BaseManager.dimensionKey(client.world))) {
			if (base.distanceTo(client.player.getX(), client.player.getY(), client.player.getZ()) <= 16) {
				here = base;
				break;
			}
		}
		if (here != null && (!here.changes.isEmpty() || Overlay.enabled)) {
			int count = here.changes.size();
			Text status = count == 0
					? Text.translatable("baseinsurance.hud.intact", here.name)
					: Text.translatable("baseinsurance.hud.damage", here.name, count);
			context.drawTextWithShadow(font, status, 4, y, count == 0 ? 0xFF55FF55 : 0xFFFF5555);
		}

		if (Overlay.enabled && client.currentScreen == null) drawAimInfo(context, font, client, manager);
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
