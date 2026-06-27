package com.phoenixprotocol.lobby;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the void lobby in the root {@code minecraft:overworld}.
 * <p>
 * The lobby is a small enclosed platform made of barrier blocks floating
 * over the void. Players cannot fall off, break blocks, or escape.
 */
public class LobbyBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger("PhoenixProtocol");
    public static final BlockPos LOBBY_CENTER = new BlockPos(0, 100, 0);
    private static final int HALF_SIZE = 3;
    private static final int WALL_HEIGHT = 4;

    public static void build(ServerLevel overworld) {
        LOGGER.info("[PhoenixProtocol] Building void lobby at ({}, {}, {})",
                LOBBY_CENTER.getX(), LOBBY_CENTER.getY(), LOBBY_CENTER.getZ());

        int cx = LOBBY_CENTER.getX();
        int cy = LOBBY_CENTER.getY();
        int cz = LOBBY_CENTER.getZ();
        int floorY = cy - 1;

        int clearRadius = 10;
        for (int x = cx - clearRadius; x <= cx + clearRadius; x++) {
            for (int z = cz - clearRadius; z <= cz + clearRadius; z++) {
                for (int y = 60; y <= 140; y++) {
                    overworld.setBlock(
                            new BlockPos(x, y, z),
                            Blocks.AIR.defaultBlockState(),
                            2
                    );
                }
            }
        }

        for (int x = cx - HALF_SIZE; x <= cx + HALF_SIZE; x++) {
            for (int z = cz - HALF_SIZE; z <= cz + HALF_SIZE; z++) {
                overworld.setBlock(
                        new BlockPos(x, floorY, z),
                        Blocks.BARRIER.defaultBlockState(),
                        2
                );
            }
        }



        for (int y = cy; y < cy + WALL_HEIGHT; y++) {
            for (int x = cx - HALF_SIZE; x <= cx + HALF_SIZE; x++) {
                for (int z = cz - HALF_SIZE; z <= cz + HALF_SIZE; z++) {
                    boolean isPerimeter =
                            x == cx - HALF_SIZE || x == cx + HALF_SIZE ||
                            z == cz - HALF_SIZE || z == cz + HALF_SIZE;

                    if (isPerimeter) {
                        overworld.setBlock(
                                new BlockPos(x, y, z),
                                Blocks.BARRIER.defaultBlockState(),
                                2
                        );
                    }
                }
            }
        }

        int ceilingY = cy + WALL_HEIGHT;
        for (int x = cx - HALF_SIZE; x <= cx + HALF_SIZE; x++) {
            for (int z = cz - HALF_SIZE; z <= cz + HALF_SIZE; z++) {
                overworld.setBlock(
                        new BlockPos(x, ceilingY, z),
                        Blocks.BARRIER.defaultBlockState(),
                        2
                );
            }
        }

        for (int x = cx - (HALF_SIZE - 1); x <= cx + (HALF_SIZE - 1); x++) {
            for (int z = cz - (HALF_SIZE - 1); z <= cz + (HALF_SIZE - 1); z++) {
                for (int y = cy + 1; y < cy + WALL_HEIGHT; y++) {
                    overworld.setBlock(
                            new BlockPos(x, y, z),
                            Blocks.AIR.defaultBlockState(),
                            2
                    );
                }
            }
        }

        for (int x = cx - HALF_SIZE; x <= cx + HALF_SIZE; x++) {
            for (int z = cz - HALF_SIZE; z <= cz + HALF_SIZE; z++) {
                overworld.setBlock(
                        new BlockPos(x, floorY - 1, z),
                        Blocks.BARRIER.defaultBlockState(),
                        2
                );
            }
        }

        LOGGER.info("[PhoenixProtocol] Void lobby built successfully");
    }
}
