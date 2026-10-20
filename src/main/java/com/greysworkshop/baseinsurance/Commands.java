package com.greysworkshop.baseinsurance;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** The /bi commands. These are client-side: they never reach the server, so your coordinates stay private. */
public final class Commands {
	private static final long MAX_VOLUME = 16_000_000;
	private static final int DEFAULT_RADIUS = 48;
	private static final int DEFAULT_DOWN = 24;
	private static final int DEFAULT_UP = 48;

	private Commands() {}

	private static BaseInsuranceClient mod() {
		return BaseInsuranceClient.INSTANCE;
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		SuggestionProvider<FabricClientCommandSource> bases = (ctx, builder) ->
				CommandSource.suggestMatching(mod().manager.all().stream().map(b -> b.name), builder);
		SuggestionProvider<FabricClientCommandSource> players = (ctx, builder) -> {
			List<String> names = new ArrayList<>();
			for (PlayerEntity player : ctx.getSource().getWorld().getPlayers()) names.add(player.getName().getString());
			return CommandSource.suggestMatching(names, builder);
		};
		SuggestionProvider<FabricClientCommandSource> friends = (ctx, builder) ->
				CommandSource.suggestMatching(BIConfig.INSTANCE.friends, builder);

		dispatcher.register(literal("bi")
				.executes(Commands::help)
				.then(literal("help").executes(Commands::help))
				.then(literal("guide").executes(c -> {
					mod().openLater(new GuideScreen(null));
					return 1;
				}))
				.then(literal("map").executes(c -> {
					mod().openLater(new MapScreen());
					return 1;
				}))
				.then(literal("create")
						.executes(c -> createOnMap(c, null))
						.then(argument("name", word())
								.executes(c -> createOnMap(c, getString(c, "name")))
								.then(argument("radius", integer(2, 256))
										.executes(c -> create(c, getInteger(c, "radius"), DEFAULT_DOWN, DEFAULT_UP))
										.then(argument("down", integer(0, 384))
												.then(argument("up", integer(0, 384))
														.executes(c -> create(c, getInteger(c, "radius"), getInteger(c, "down"), getInteger(c, "up"))))))))
				.then(literal("delete")
						.then(argument("name", word()).suggests(bases)
								.executes(c -> confirmHint(c, "delete", "permanently deletes this base and its history"))
								.then(literal("confirm").executes(Commands::delete))))
				.then(literal("list").executes(Commands::list))
				.then(literal("info").executes(Commands::info))
				.then(literal("report")
						.executes(c -> report(c, null))
						.then(argument("name", word()).suggests(bases).executes(c -> report(c, getString(c, "name")))))
				.then(literal("overlay").executes(Commands::overlay))
				.then(literal("build").executes(Commands::build))
				.then(literal("accept")
						.executes(c -> accept(c, null, -1))
						.then(argument("name", word()).suggests(bases)
								.executes(c -> accept(c, getString(c, "name"), -1))
								.then(argument("radius", integer(1, 512))
										.executes(c -> accept(c, getString(c, "name"), getInteger(c, "radius"))))))
				.then(literal("snapshot")
						.then(argument("name", word()).suggests(bases)
								.executes(c -> confirmHint(c, "snapshot", "throws away the current snapshot and change list and takes a fresh one"))
								.then(literal("confirm").executes(Commands::resnapshot))))
				.then(literal("materials")
						.executes(c -> materials(c, null))
						.then(argument("name", word()).suggests(bases).executes(c -> materials(c, getString(c, "name")))))
				.then(literal("friend")
						.then(literal("add").then(argument("player", word()).suggests(players).executes(c -> friend(c, true))))
						.then(literal("remove").then(argument("player", word()).suggests(friends).executes(c -> friend(c, false))))
						.then(literal("list").executes(Commands::friendList))));
	}

	private static int help(CommandContext<FabricClientCommandSource> c) {
		FabricClientCommandSource s = c.getSource();
		s.sendFeedback(Text.literal("Base Insurance commands (client-side, never sent to the server):").formatted(Formatting.GOLD));
		String[][] lines = {
				{"/bi create [name]", "pick an area to protect on the map"},
				{"/bi create <name> <radius> [down] [up]", "protect the area around you right away"},
				{"/bi map", "your explored world, bases and damage (or press M)"},
				{"/bi guide", "how to get started"},
				{"/bi report [name]", "open the damage report"},
				{"/bi overlay", "show/hide changed blocks in the world"},
				{"/bi build", "build mode: your big changes (TNT, pistons, water) go straight into the snapshot"},
				{"/bi accept [name] [radius]", "accept the current changes as the new normal"},
				{"/bi materials [name]", "what you need to rebuild and where it is"},
				{"/bi list, /bi info", "your bases / the base you're in"},
				{"/bi friend add|remove|list", "friends don't trigger alerts"},
				{"/bi snapshot <name> confirm", "retake the snapshot from scratch"},
				{"/bi delete <name> confirm", "delete a base"},
		};
		for (String[] line : lines) {
			s.sendFeedback(Text.literal(line[0]).formatted(Formatting.YELLOW).append(Text.literal(" — " + line[1]).formatted(Formatting.GRAY)));
		}
		return 1;
	}

	private static int create(CommandContext<FabricClientCommandSource> c, int radius, int down, int up) {
		return create(c, getString(c, "name"), radius, down, up);
	}

	/** With no name given, uses "Home", or "Home2", "Home3"... if that's taken. */
	private static int create(CommandContext<FabricClientCommandSource> c, String name, int radius, int down, int up) {
		FabricClientCommandSource s = c.getSource();
		if (name == null) name = defaultName();
		BlockPos center = s.getPlayer().getBlockPos();
		String error = createBase(name, s.getWorld(), center, center.getX() - radius, center.getX() + radius,
				center.getZ() - radius, center.getZ() + radius, center.getY() - down, center.getY() + up, s::sendFeedback);
		if (error != null) {
			s.sendError(Text.literal(error));
			return 0;
		}
		return 1;
	}

	/** Opens the map so you can draw the base's area; used by /bi create without a size. */
	private static int createOnMap(CommandContext<FabricClientCommandSource> c, String name) {
		if (mod().manager.worldKey() == null) {
			c.getSource().sendError(Text.literal("Join a world or server first."));
			return 0;
		}
		mod().openLater(MapScreen.forCreate(name == null ? defaultName() : name));
		return 1;
	}

	/** "Home", or "Home2", "Home3"... if that's taken. */
	public static String defaultName() {
		String name = "Home";
		for (int i = 2; mod().manager.get(name) != null; i++) name = "Home" + i;
		return name;
	}

	/** Checks a proposed base; returns why it can't be created, or null if it's fine. */
	public static String validate(String name, long volume) {
		if (!BaseManager.isValidName(name)) return "Names can use letters, numbers, - and _ (up to 32).";
		if (mod().manager.worldKey() == null) return "Join a world or server first.";
		if (mod().manager.get(name) != null) return "You already have a base called " + name + ".";
		if (volume > MAX_VOLUME) return "That's " + volume / 1_000_000 + " million blocks; the limit is 16 million. Make it smaller or less tall.";
		return null;
	}

	/** Creates and starts snapshotting a base. Returns an error message, or null on success. */
	public static String createBase(String name, ClientWorld world, BlockPos center, int minX, int maxX, int minZ, int maxZ,
			int minY, int maxY, java.util.function.Consumer<Text> feedback) {
		Base base = new Base();
		base.name = name;
		base.dimension = BaseManager.dimensionKey(world);
		base.minX = Math.min(minX, maxX);
		base.maxX = Math.max(minX, maxX);
		base.minZ = Math.min(minZ, maxZ);
		base.maxZ = Math.max(minZ, maxZ);
		base.minY = Math.max(world.getBottomY(), Math.min(minY, maxY));
		base.maxY = Math.min(world.getTopYInclusive(), Math.max(minY, maxY));
		String error = validate(name, base.volume());
		if (error != null) return error;
		base.created = base.lastSeen = System.currentTimeMillis();

		BaseManager manager = mod().manager;
		int seeded = seedContainers(base, manager.worldKey(), world);
		String size = (base.maxX - base.minX + 1) + "×" + (base.maxY - base.minY + 1) + "×" + (base.maxZ - base.minZ + 1);
		base.log(Base.EventType.CREATED, "Base created around " + Alerts.pos(center) + " (" + size + ")");
		manager.add(base);

		feedback.accept(Text.literal("⚑ " + name + " is now insured: " + size + " blocks.").formatted(Formatting.GREEN));
		feedback.accept(Text.literal("The snapshot is taken over the next few seconds. Parts that aren't loaded yet are captured when you get near them.").formatted(Formatting.GRAY));
		if (seeded > 0) {
			feedback.accept(Text.literal("Imported contents of " + Alerts.plural(seeded, "container") + " from Container Peek.").formatted(Formatting.GRAY));
		}
		feedback.accept(Text.literal("Open each chest once so its contents are on record. Tip: /bi friend add <name> for people who share the base.").formatted(Formatting.GRAY));
		return null;
	}

	/** Starts container records from what Container Peek already knows about storage in this area. */
	private static int seedContainers(Base base, String worldKey, ClientWorld world) {
		int seeded = 0;
		for (Map.Entry<Long, Map<String, Integer>> entry : ContainerPeekBridge.containers(worldKey, base.dimension).entrySet()) {
			BlockPos pos = BlockPos.fromLong(entry.getKey());
			if (!base.contains(pos)) continue;
			boolean loaded = world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
			if (loaded && !ContainerWatcher.isStorage(world.getBlockEntity(pos))) continue;
			Base.ContainerSnapshot snapshot = new Base.ContainerSnapshot();
			snapshot.block = loaded ? Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString() : "minecraft:chest";
			snapshot.items = new java.util.LinkedHashMap<>(entry.getValue());
			snapshot.updated = System.currentTimeMillis();
			base.containers.put(entry.getKey(), snapshot);
			seeded++;
		}
		return seeded;
	}

	private static int confirmHint(CommandContext<FabricClientCommandSource> c, String action, String what) {
		Base base = named(c, getString(c, "name"));
		if (base == null) return 0;
		c.getSource().sendFeedback(Text.literal("This " + what + ". To go ahead: /bi " + action + " " + base.name + " confirm").formatted(Formatting.YELLOW));
		return 1;
	}

	private static int delete(CommandContext<FabricClientCommandSource> c) {
		Base base = named(c, getString(c, "name"));
		if (base == null) return 0;
		mod().manager.delete(base);
		c.getSource().sendFeedback(Text.literal("Deleted " + base.name + ".").formatted(Formatting.GRAY));
		return 1;
	}

	private static int resnapshot(CommandContext<FabricClientCommandSource> c) {
		Base base = named(c, getString(c, "name"));
		if (base == null) return 0;
		base.baseline.sections.clear();
		base.changes.clear();
		base.loadedSections.clear();
		base.baselineDirty = true;
		base.log(Base.EventType.ACCEPTED, "Snapshot retaken from scratch");
		c.getSource().sendFeedback(Text.literal("Retaking the snapshot of " + base.name + ".").formatted(Formatting.GREEN));
		return 1;
	}

	private static int list(CommandContext<FabricClientCommandSource> c) {
		FabricClientCommandSource s = c.getSource();
		List<Base> all = mod().manager.all();
		if (all.isEmpty()) {
			s.sendFeedback(Text.literal("No bases yet. Stand in your base and run /bi create <name>.").formatted(Formatting.GRAY));
			return 1;
		}
		for (Base base : all) {
			int changes = base.changes.size();
			s.sendFeedback(Text.literal(base.name).formatted(Formatting.YELLOW)
					.append(Text.literal(" " + base.dimension.replace("minecraft:", "") + ", last visit " + Alerts.ago(base.lastSeen) + " — ").formatted(Formatting.GRAY))
					.append(changes == 0
							? Text.literal("intact").formatted(Formatting.GREEN)
							: Text.literal(changes + " changes").formatted(Formatting.RED)));
		}
		return 1;
	}

	private static int info(CommandContext<FabricClientCommandSource> c) {
		Base base = current(c);
		if (base == null) return 0;
		FabricClientCommandSource s = c.getSource();
		s.sendFeedback(Text.literal("⚑ " + base.name).formatted(Formatting.GOLD)
				.append(Text.literal(" from " + base.minX + " " + base.minY + " " + base.minZ + " to " + base.maxX + " " + base.maxY + " " + base.maxZ).formatted(Formatting.GRAY)));
		s.sendFeedback(Text.literal(base.baseline.sections.size() + "/" + base.sections().size() + " sections captured, "
				+ base.containers.size() + " containers on record, " + base.changes.size() + " changes.").formatted(Formatting.GRAY));
		return 1;
	}

	private static int report(CommandContext<FabricClientCommandSource> c, String name) {
		Base base = name == null ? current(c) : named(c, name);
		if (base == null) return 0;
		mod().openLater(new ReportScreen(base));
		return 1;
	}

	private static int overlay(CommandContext<FabricClientCommandSource> c) {
		Overlay.enabled = !Overlay.enabled;
		c.getSource().sendFeedback(Text.literal("Damage overlay " + (Overlay.enabled ? "ON" : "OFF")).formatted(Formatting.GRAY));
		return 1;
	}

	private static int build(CommandContext<FabricClientCommandSource> c) {
		Scanner scanner = mod().scanner;
		scanner.buildMode = !scanner.buildMode;
		c.getSource().sendFeedback(scanner.buildMode
				? Text.literal("Build mode ON: every change you see in your bases goes into the snapshot. Turn it off when you're done!").formatted(Formatting.YELLOW)
				: Text.literal("Build mode OFF: changes are tracked again.").formatted(Formatting.GREEN));
		return 1;
	}

	private static int accept(CommandContext<FabricClientCommandSource> c, String name, int radius) {
		Base base = name == null ? current(c) : named(c, name);
		if (base == null) return 0;
		int accepted = acceptChanges(base, radius > 0 ? c.getSource().getPlayer().getBlockPos() : null, radius);
		c.getSource().sendFeedback(Text.literal("Accepted " + Alerts.plural(accepted, "change") + " into " + base.name + "'s snapshot. Undo it from the report.").formatted(Formatting.GREEN));
		return 1;
	}

	/** Makes the current state the new normal, for all changes or those within {@code radius} of {@code center}. */
	public static int acceptChanges(Base base, BlockPos center, int radius) {
		return center == null ? BaseActions.acceptAll(base) : BaseActions.acceptNear(base, center, radius);
	}

	private static int materials(CommandContext<FabricClientCommandSource> c, String name) {
		Base base = name == null ? current(c) : named(c, name);
		if (base == null) return 0;
		FabricClientCommandSource s = c.getSource();
		List<Materials.Need> needs = Materials.compute(base, mod().manager, MinecraftClient.getInstance());
		if (needs.isEmpty()) {
			s.sendFeedback(Text.literal("Nothing to rebuild in " + base.name + ".").formatted(Formatting.GREEN));
			return 1;
		}
		s.sendFeedback(Text.literal("To restore " + base.name + ":").formatted(Formatting.GOLD));
		for (Materials.Need need : needs.subList(0, Math.min(12, needs.size()))) {
			Formatting color = need.carried() >= need.needed() ? Formatting.GREEN
					: need.carried() + need.stored() >= need.needed() ? Formatting.YELLOW : Formatting.RED;
			String where = need.sources().isEmpty() ? "" : " — nearest: " + need.sources().get(0).label() + " at " + Alerts.pos(need.sources().get(0).pos());
			s.sendFeedback(Text.literal(need.item().getName().getString() + ": need " + need.needed() + ", carrying " + need.carried()
					+ ", stored " + need.stored()).formatted(color).append(Text.literal(where).formatted(Formatting.GRAY)));
		}
		if (needs.size() > 12) s.sendFeedback(Text.literal("…and " + (needs.size() - 12) + " more. See the Rebuild tab in /bi report.").formatted(Formatting.GRAY));
		return 1;
	}

	private static int friend(CommandContext<FabricClientCommandSource> c, boolean add) {
		String player = getString(c, "player");
		List<String> friends = BIConfig.INSTANCE.friends;
		if (add) {
			if (!BIConfig.INSTANCE.isFriend(player)) friends.add(player);
			c.getSource().sendFeedback(Text.literal(player + " is now a friend: no alerts for them.").formatted(Formatting.GREEN));
		} else {
			friends.removeIf(f -> f.equalsIgnoreCase(player));
			c.getSource().sendFeedback(Text.literal(player + " removed from friends.").formatted(Formatting.GRAY));
		}
		BIConfig.save();
		return 1;
	}

	private static int friendList(CommandContext<FabricClientCommandSource> c) {
		List<String> friends = BIConfig.INSTANCE.friends;
		c.getSource().sendFeedback(Text.literal(friends.isEmpty() ? "No friends added yet." : "Friends: " + String.join(", ", friends)).formatted(Formatting.GRAY));
		return 1;
	}

	private static Base named(CommandContext<FabricClientCommandSource> c, String name) {
		Base base = mod().manager.get(name);
		if (base == null) c.getSource().sendError(Text.literal("No base called " + name + ". /bi list shows your bases."));
		return base;
	}

	/** The base you're in, or the nearest one in this dimension. */
	private static Base current(CommandContext<FabricClientCommandSource> c) {
		Base base = mod().currentBase();
		if (base == null) c.getSource().sendError(Text.literal("No bases in this dimension. Create one with /bi create <name>."));
		return base;
	}
}
