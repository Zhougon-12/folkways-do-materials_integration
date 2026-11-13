package com.empire.folkways.ornamentum;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class OrnamentumCommand {
    private OrnamentumCommand() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("folkways_ornamentum")
                .then(Commands.literal("selftest")
                        .executes(context -> OrnamentumSelfTest.runFromCommand(context.getSource(), "ornamentum_test"))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> OrnamentumSelfTest.runFromCommand(
                                        context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("release")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(context -> releaseAt(context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "x"),
                                                        IntegerArgumentType.getInteger(context, "y"),
                                                        IntegerArgumentType.getInteger(context, "z")))))))
                .then(Commands.literal("status")
                        .executes(context -> {
                            OrnamentumSelfTest.logStatus(context.getSource().getServer());
                            return 1;
                        }))
        );
    }

    private static int releaseAt(CommandSourceStack source, int x, int y, int z) {
        int released = 0;
        for (var colony : io.github.izakyl.folkways.core.colony.api.Colonies.all(source.getServer())) {
            for (var claim : colony.claims()) {
                for (var pos : claim.what().cells()) {
                    if (pos.cell().getX() == x && pos.cell().getY() == y && pos.cell().getZ() == z) {
                        colony.release(claim.id(),
                                io.github.izakyl.folkways.core.colony.api.ClaimRelease.LET_GO);
                        released++;
                        break;
                    }
                }
            }
        }
        final int count = released;
        source.sendSuccess(() -> Component.literal("released claims: " + count), false);
        return count;
    }
}
