package com.example.storagelogger;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.*;
import net.minecraft.block.entity.*;
import net.minecraft.command.argument.TimeArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class StorageLoggerMod implements ModInitializer {
    private static final String LOGS_DIR = "mods/storage_logger_logs";
    private static final String LOG_FILE = "storage_logs.txt";
    private static File logFile;
    private static boolean loggingEnabled = true;
    private static boolean logEmptyContainers = false;
    private static Set<String> filteredPlayers = new HashSet<>();
    private static Set<String> filteredBlocks = new HashSet<>();
    private static Set<RegistryKey<World>> allowedDimensions = new HashSet<>();

    @Override
    public void onInitialize() {
        initializeLogsDirectory();
        registerBlockInteractionHandler();
        registerCommands();
        
        // По умолчанию разрешены все измерения
        allowedDimensions.add(World.OVERWORLD);
        allowedDimensions.add(World.NETHER);
        allowedDimensions.add(World.END);
    }

    private void initializeLogsDirectory() {
        File logsDir = new File(LOGS_DIR);
        if (!logsDir.exists()) logsDir.mkdirs();
        logFile = new File(logsDir, LOG_FILE);
    }

    private void registerBlockInteractionHandler() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!loggingEnabled || world.isClient()) return ActionResult.PASS;

            RegistryKey<World> dimension = world.getRegistryKey();
            if (!allowedDimensions.contains(dimension)) return ActionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            BlockState state = world.getBlockState(pos);
            Block block = state.getBlock();

            if (isSupportedContainer(block) && matchesFilters(player.getName().getString(), block)) {
                logContainerInteraction(world, world.getBlockEntity(pos), pos, player.getName().getString(), block);
            }
            return ActionResult.PASS;
        });
    }

    private void logContainerInteraction(World world, BlockEntity blockEntity, BlockPos pos, String playerName, Block block) {
        String dimensionName = getDimensionName(world.getRegistryKey());
        String blockName = getBlockName(block);
        List<String> items = getCompressedItems(blockEntity);
        boolean isEmpty = items.isEmpty();
        
        if (!logEmptyContainers && isEmpty && !(blockEntity instanceof ShulkerBoxBlockEntity)) {
            return;
        }

        writeToLog(dimensionName, blockName, pos.getX(), pos.getY(), pos.getZ(), playerName, items);
    }

    private String getDimensionName(RegistryKey<World> dimension) {
        if (dimension == World.OVERWORLD) return "Overworld";
        if (dimension == World.NETHER) return "Nether";
        if (dimension == World.END) return "End";
        return dimension.getValue().getPath(); // Для кастомных измерений
    }

    private void writeToLog(String dimension, String blockName, int x, int y, int z, String playerName, List<String> items) {
        try (FileWriter writer = new FileWriter(logFile, true)) {
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            writer.write(String.format("[%s] %s at [x:%d y:%d z:%d] opened by %s at %s\n",
                dimension, blockName, x, y, z, playerName, time));
            
            if (!items.isEmpty()) {
                writer.write("  Contents: " + String.join(", ", items) + "\n");
            }
        } catch (IOException e) {
            System.err.println("Failed to write log: " + e.getMessage());
        }
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(CommandManager.literal("storage-logger")
                .then(CommandManager.literal("start")
                    .executes(ctx -> startLogging(ctx.getSource())))
                .then(CommandManager.literal("stop")
                    .executes(ctx -> stopLogging(ctx.getSource())))
                .then(CommandManager.literal("filter-dimension")
                    .then(CommandManager.literal("overworld")
                        .executes(ctx -> toggleDimension(ctx.getSource(), World.OVERWORLD)))
                    .then(CommandManager.literal("nether")
                        .executes(ctx -> toggleDimension(ctx.getSource(), World.NETHER)))
                    .then(CommandManager.literal("end")
                        .executes(ctx -> toggleDimension(ctx.getSource(), World.END)))));
        });
    }

    private int toggleDimension(ServerCommandSource source, RegistryKey<World> dimension) {
        if (allowedDimensions.contains(dimension)) {
            allowedDimensions.remove(dimension);
            source.sendFeedback(() -> Text.literal("Disabled logging for dimension: " + dimension.getValue()), false);
        } else {
            allowedDimensions.add(dimension);
            source.sendFeedback(() -> Text.literal("Enabled logging for dimension: " + dimension.getValue()), false);
        }
        return 1;
    }
}
