package com.taun;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

public class TaunCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                ClientCommandManager.literal("pest")
                    // /pest reload
                    .then(ClientCommandManager.literal("reload")
                        .executes(ctx -> { TaunCore.reloadConfig(); return 1; })
                    )
                    // /pest toggle chat | coords | all
                    .then(ClientCommandManager.literal("toggle")
                        .then(ClientCommandManager.literal("chat")
                            .executes(ctx -> { TaunCore.toggleChatTriggers(); return 1; })
                        )
                        .then(ClientCommandManager.literal("coords")
                            .executes(ctx -> { TaunCore.toggleCoordTriggers(); return 1; })
                        )
                        .then(ClientCommandManager.literal("all")
                            .executes(ctx -> { TaunCore.toggleAllTriggers(); return 1; })
                        )
                    )
                    // /pest rodswap
                    .then(ClientCommandManager.literal("rodswap")
                        .executes(ctx -> { TaunCore.toggleRodswap(); return 1; })
                        .then(ClientCommandManager.literal("rosedrag")
                            .executes(ctx -> { TaunCore.toggleRosedrag(); return 1; })
                        )
                    )
                    // /pest wdswap
                    .then(ClientCommandManager.literal("wdswap")
                        .executes(ctx -> { TaunCore.toggleWardrobeSwap(); return 1; })
                    )
                    // /pest etherwarp
                    .then(ClientCommandManager.literal("etherwarp")
                        .executes(ctx -> { TaunCore.toggleEtherwarp(); return 1; })
                    )
                    // /pest setetherwarp
                    .then(ClientCommandManager.literal("setetherwarp")
                        .executes(ctx -> { TaunCore.startSetEtherwarpCoords(); return 1; })
                    )
                    // /pest eqswap
                    .then(ClientCommandManager.literal("eqswap")
                        .executes(ctx -> { TaunCore.toggleEqSwap(); return 1; })
                        .then(ClientCommandManager.literal("zorro")
                            .executes(ctx -> { TaunCore.toggleZorro(); return 1; })
                        )
                    )
                    // /pest debug
                    .then(ClientCommandManager.literal("debug")
                        .executes(ctx -> { TaunCore.toggleDebug(); return 1; })
                    )
                    // /pest dynarest — toggle on/off
                    // /pest dynarest <time> — set scripting time (e.g. 2h, 2.5h, 2.5 hours, 90m, 90)
                    // /pest dynarest status | breaktime <m> | scriptoffset <m>
                    .then(ClientCommandManager.literal("dynarest")
                        .executes(ctx -> { TaunCore.toggleDynamicRest(); return 1; })
                        .then(ClientCommandManager.literal("status")
                            .executes(ctx -> { TaunCore.showDynRestStatus(); return 1; })
                        )
                        .then(ClientCommandManager.literal("breaktime")
                            .then(ClientCommandManager.argument("minutes", IntegerArgumentType.integer(1, 999))
                                .executes(ctx -> {
                                    TaunCore.setDynRestBreakTime(IntegerArgumentType.getInteger(ctx, "minutes"));
                                    return 1;
                                })
                            )
                        )
                        .then(ClientCommandManager.literal("scriptoffset")
                            .then(ClientCommandManager.argument("minutes", IntegerArgumentType.integer(0, 60))
                                .executes(ctx -> {
                                    TaunCore.setDynRestScriptingOffset(IntegerArgumentType.getInteger(ctx, "minutes"));
                                    return 1;
                                })
                            )
                        )
                        .then(ClientCommandManager.argument("time", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                TaunCore.setDynRestScriptingTimeFromString(StringArgumentType.getString(ctx, "time"));
                                return 1;
                            })
                        )
                    )
                    // /pest rotatespeed <ms>
                    .then(ClientCommandManager.literal("rotatespeed")
                        .then(ClientCommandManager.argument("ms", LongArgumentType.longArg(50, 5000))
                            .executes(ctx -> {
                                TaunCore.setRotateSpeed(LongArgumentType.getLong(ctx, "ms"));
                                return 1;
                            })
                        )
                    )
                    // /pest setup
                    .then(ClientCommandManager.literal("setup")
                        .executes(ctx -> { TaunCore.startSetupWizard(); return 1; })
                    )
                    // /pest setspawn
                    .then(ClientCommandManager.literal("setspawn")
                        .executes(ctx -> { TaunCore.addSetspawnCoords(); return 1; })
                    )
                    // /pest setend
                    .then(ClientCommandManager.literal("setend")
                        .executes(ctx -> { TaunCore.addSetendCoords(); return 1; })
                    )
                    // /pest setspawntrigger
                    .then(ClientCommandManager.literal("setspawntrigger")
                        .executes(ctx -> { TaunCore.addSetSpawnTriggerCoords(); return 1; })
                    )
                    // /pest files
                    .then(ClientCommandManager.literal("files")
                        .executes(ctx -> { TaunCore.openConfigFolder(); return 1; })
                    )
                    // /pest detect
                    .then(ClientCommandManager.literal("detect")
                        .executes(ctx -> { TaunCore.showDetectedTools(); return 1; })
                    )

                    // /pest random <ms> — add ±<ms> random variance to all delays at runtime
                    .then(ClientCommandManager.literal("random")
                        .executes(ctx -> { TaunCore.showRandomDelay(); return 1; })
                        .then(ClientCommandManager.argument("variance", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String input = StringArgumentType.getString(ctx, "variance").trim().toLowerCase();
                                int ms;
                                try {
                                    if (input.endsWith("ms")) ms = Integer.parseInt(input.substring(0, input.length() - 2).trim());
                                    else if (input.endsWith("s")) ms = (int)(Long.parseLong(input.substring(0, input.length() - 1).trim()) * 1000);
                                    else ms = Integer.parseInt(input);
                                } catch (NumberFormatException e) { return 1; }
                                TaunCore.setRandomDelay(ms);
                                return 1;
                            })
                        )
                    )

                    // /pest help
                    .then(ClientCommandManager.literal("help")
                        .executes(ctx -> { TaunCore.showHelp(); return 1; })
                    )
                    .then(ClientCommandManager.literal("extrasell")
                        .executes(ctx -> { TaunCore.toggleExtraSell(); return 1; })
                    )
                    .then(ClientCommandManager.literal("georgesell")
                        .executes(ctx -> { TaunCore.toggleGeorgeSlugSell(); return 1; })
                    )
            );
        });
    }
}
