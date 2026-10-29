package com.greysworkshop.baseinsurance.mixin;

import com.greysworkshop.baseinsurance.Breaks;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldEventS2CPacket;
import net.minecraft.world.WorldEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server telling us a block was really broken, or blew up: what a chest hider never sends. (At the end: on the game thread.) */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
	@Inject(method = "onWorldEvent", at = @At("TAIL"))
	private void baseinsurance$blockBroken(WorldEventS2CPacket packet, CallbackInfo ci) {
		if (packet.getEventId() == WorldEvents.BLOCK_BROKEN) Breaks.broken(packet.getPos());
	}

	/** Item frames, armor stands and paintings make these sounds, for everyone near, when really broken or emptied. */
	@Inject(method = "onPlaySound", at = @At("TAIL"))
	private void baseinsurance$entityBroken(net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket packet, CallbackInfo ci) {
		Breaks.sound(packet.getSound(), packet.getX(), packet.getY(), packet.getZ());
	}

	@Inject(method = "onExplosion", at = @At("TAIL"))
	private void baseinsurance$explosion(ExplosionS2CPacket packet, CallbackInfo ci) {
		Breaks.exploded(packet.center(), packet.radius());
	}
}
