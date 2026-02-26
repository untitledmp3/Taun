package com.taun;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Properties;

public class TaunCore implements ClientModInitializer {
    public static final String MOD_ID = "Taun+++";
    private static final int CONFIG_VERSION = 15;
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static final List<ChatTrigger> triggers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final List<CoordinateTrigger> coordinateTriggers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final List<PestCdTrigger> pestCdTriggers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static Path configPath;
    private static Path coordConfigPath;
    private static Path settingsPath;
    
    private static long worldJoinTime = 0;
    private static final long WORLD_JOIN_GRACE_MS = 15000;
    
    private static String lastMessage = "";
    private static long lastMessageTime = 0;
    private static final long COOLDOWN_MS = 1000;
    
    private static final Map<String, Long> globalTriggerCooldowns = new HashMap<>();
    private static final long GLOBAL_TRIGGER_COOLDOWN = 100;
    
    private static final Map<String, Long> chatTriggerCooldowns = new HashMap<>();
    private static final long CHAT_TRIGGER_COOLDOWN = 500;
    
    private static boolean chatTriggersEnabled = true;
    private static boolean rodswapEnabled = false;
    private static boolean rosedragEnabled = false;
    private static boolean wardrobeSwapEnabled = false;
    private static boolean etherwarpEnabled = false;
    private static float etherwarpYaw = -95f;   // center yaw, ±10 applied when writing triggers
    private static float etherwarpPitch = -70f; // center pitch, ±10 applied, clamped to -90
    private static volatile boolean settingEtherwarpCoords = false; // true while waiting for RMB to capture coords
    private static long rotateSpeedMs = 250;
    private static boolean coordTriggersEnabled = true;
    
    private static boolean lastPestCooldownReady = false;
    private static long lastPestReadyTime = 0;
    private static final long PEST_READY_COOLDOWN = 2000;
    private static String lastPestCooldownText = "";
    private static boolean eqSwapEnabled = false;
    private static boolean finneganMode = false; // kept for settings migration, no longer used
    private static boolean debugEnabled = false;
    private static long lastEqSwapFireTime = 0;
    private static final long EQ_SWAP_COOLDOWN_MS = 15 * 1000;
    private static boolean isFarming = false;

    // ── Dynamic Rest settings ──────────────────────────────────────────────────
    private static boolean dynamicRestEnabled = false;
    private static int restScriptingTime = 30;
    private static int restScriptingTimeOffset = 3;
    private static int restBreakTime = 10;


    /**
     * dynRestMode controls what the mod does when a break is triggered:
     *   "pause"      — stops the farming script (.ez-stopscript), waits out the break,
     *                  then restarts it (.ez-startscript netherwart:1).
     *   "disconnect" — runs /setspawn first, then disconnects from the server entirely,
     *                  waits out the break offline, then reconnects and restarts the script.
     */
    private static String dynRestMode = "disconnect";

    // ── Slug inventory monitoring ─────────────────────────────────────────────

    /** Counts how many [Lvl 1] Slug items are in the player's full inventory (slots 0-35). */
    private static int countSlugsInInventory(MinecraftClient mc) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            var stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getName().getString().replaceAll("§.", "").toLowerCase().contains("[lvl 1] slug")) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Returns true if the player's inventory (slots 9-35, excluding hotbar) is completely full. */
    private static boolean isInventoryFull(MinecraftClient mc) {
        if (mc.player == null) return false;
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return false;
        }
        return true;
    }

    private static void checkSlugInventory(MinecraftClient client) {
        if (!isFarming) return; // only sell while farming
        if (!georgeSlugSellEnabled) return; // disabled by /pest georgesell
        int slugs = countSlugsInInventory(client);
        if (slugs <= 0) return;
        boolean full = isInventoryFull(client);
        if (full && slugs < SLUG_SELL_THRESHOLD) return; // inventory full but not enough slugs yet — AutoSell will handle it
        if (slugs < SLUG_SELL_THRESHOLD) return;
        // Threshold reached — sell immediately
        georgeSlugSellActive = true;
        lastGeorgeSlugSellTime = System.currentTimeMillis();
        final int slugCount = slugs;
        new Thread(() -> {
            try {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null) { georgeSlugSellActive = false; return; }
                mc.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Selling slugs (" + slugCount + " slugs in inventory)..."), false);
                mc.execute(() -> { if (mc.player != null) mc.player.networkHandler.sendChatMessage(".ez-stopscript"); });
                Thread.sleep(500);
                triggerGeorgeSlugSell();
                Thread.sleep(300);
                triggerBoosterCookie();
                mc.execute(() -> { if (mc.player != null) mc.player.networkHandler.sendChatMessage(".ez-startscript netherwart:1"); });
            } catch (Exception e) {
                LOGGER.warn("[SlugSell] Failed: {}", e.getMessage());
            } finally {
                georgeSlugSellActive = false;
            }
        }, "taun-slug-sell").start();
    }

    /**
     * Sells [Lvl 1] Slug pets via /call george.
     * Flow:
     *   1. /call george
     *   2. Wait 3 seconds for GUI to open
     *   3. Scan GUI for [Lvl 1] Slug → shift-click to sell
     *   4. Look for green confirm button → click
     *   5. Close GUI
     */
    private static void triggerGeorgeSlugSell() throws InterruptedException {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        while (countSlugsInInventory(mc) > 0) {
            if (mc.player == null) return;

            // Call george and wait for GUI
            georgeRingDetected = false;
            mc.execute(() -> { if (mc.player != null) mc.player.networkHandler.sendChatCommand("call george"); });
            if (debugEnabled) mc.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Sent /call george, waiting for GUI..."), false);

            long guiDeadline = System.currentTimeMillis() + 10000;
            while (mc.currentScreen == null && System.currentTimeMillis() < guiDeadline) Thread.sleep(100);
            if (mc.currentScreen == null) {
                LOGGER.warn("[SlugSell] No GUI appeared after /call george — aborting");
                if (mc.player != null) mc.player.sendMessage(Text.literal("§c§lTaun+++ >> §c/call george timed out — returning to farming"), false);
                return;
            }
            Thread.sleep(300); // let slots populate

            // Find one [Lvl 1] Slug in the GUI
            java.util.concurrent.atomic.AtomicInteger slugSlot = new java.util.concurrent.atomic.AtomicInteger(-1);
            java.util.concurrent.CountDownLatch scanLatch = new java.util.concurrent.CountDownLatch(1);
            mc.execute(() -> {
                try {
                    if (mc.player.currentScreenHandler != null) {
                        var slots = mc.player.currentScreenHandler.slots;
                        for (int i = 0; i < slots.size(); i++) {
                            var slot = slots.get(i);
                            if (!slot.hasStack()) continue;
                            String name = slot.getStack().getName().getString().replaceAll("§.", "").toLowerCase();
                            if (name.contains("[lvl 1] slug")) { slugSlot.set(i); break; }
                        }
                    }
                } finally { scanLatch.countDown(); }
            });
            scanLatch.await();

            if (slugSlot.get() == -1) {
                // No slug found in GUI — close and stop
                mc.execute(() -> { if (mc.currentScreen != null) mc.currentScreen.close(); });
                break;
            }

            // Shift-click the slug to bring up confirm screen
            final int ss = slugSlot.get();
            var originalHandler = mc.player.currentScreenHandler; // capture BEFORE queuing click
            mc.execute(() -> {
                if (mc.player.currentScreenHandler != null)
                    mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, ss, 0,
                        net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
            });

            // Wait for the confirm screen to open (screen handler changes)
            long confirmDeadline = System.currentTimeMillis() + 1000;
            while (mc.player.currentScreenHandler == originalHandler && System.currentTimeMillis() < confirmDeadline) Thread.sleep(50);
            Thread.sleep(300); // let slots populate

            // Click the green confirm button twice (2 confirmations), 1s apart
            boolean confirmFailed = false;
            for (int confirmation = 0; confirmation < 2; confirmation++) {
                java.util.concurrent.atomic.AtomicInteger greenSlot = new java.util.concurrent.atomic.AtomicInteger(-1);
                java.util.concurrent.CountDownLatch greenLatch = new java.util.concurrent.CountDownLatch(1);
                mc.execute(() -> {
                    try {
                        if (mc.player.currentScreenHandler != null) {
                            var slots = mc.player.currentScreenHandler.slots;
                            for (int i = 0; i < slots.size(); i++) {
                                var slot = slots.get(i);
                                if (!slot.hasStack()) continue;
                                String itemId = net.minecraft.registry.Registries.ITEM.getId(slot.getStack().getItem()).toString().toLowerCase();
                                String itemName = slot.getStack().getName().getString().replaceAll("§.", "").toLowerCase();
                                if (itemId.contains("lime") || itemId.contains("green") || itemId.contains("terracotta") || itemName.contains("confirm") || itemName.contains("yes") || itemName.contains("accept") || itemName.contains("click to accept")) {
                                    greenSlot.set(i); break;
                                }
                            }
                        }
                    } finally { greenLatch.countDown(); }
                });
                greenLatch.await();

                if (greenSlot.get() != -1) {
                    final int gs = greenSlot.get();
                    if (debugEnabled) mc.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Confirm " + (confirmation + 1) + " at slot " + gs), false);
                    mc.execute(() -> {
                        if (mc.player.currentScreenHandler != null)
                            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, gs, 0,
                                net.minecraft.screen.slot.SlotActionType.PICKUP, mc.player);
                    });
                    if (confirmation == 0) Thread.sleep(1000); // wait 1s before second confirmation
                } else {
                    LOGGER.warn("[SlugSell] No confirm button found on confirmation {} — stopping", confirmation + 1);
                    mc.execute(() -> { if (mc.currentScreen != null) mc.currentScreen.close(); });
                    confirmFailed = true;
                    break;
                }
            }
            if (confirmFailed) break;
            // Wait for GUI to close before looping
            long closeDeadline = System.currentTimeMillis() + 3000;
            while (mc.currentScreen != null && System.currentTimeMillis() < closeDeadline) Thread.sleep(100);
        }

        georgeRingDetected = false;
        if (mc.player != null) mc.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Slug sell complete!"), false);
        LOGGER.info("[SlugSell] Sell sequence complete.");
    }

    private static boolean boosterCookieEnabled = true;
    private static final java.util.List<String> BOOSTER_COOKIE_ITEMS = java.util.List.of(
        "wriggling larva", "chirping stereo", "mantid claw", "overclocker", "chip"
    );

    private static void triggerBoosterCookie() throws InterruptedException {
        if (!boosterCookieEnabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        // Check if any target items are in inventory before opening GUI
        boolean hasItems = false;
        if (mc.player != null) {
            for (int i = 0; i < 36; i++) {
                var stack = mc.player.getInventory().getStack(i);
                if (stack.isEmpty()) continue;
                String name = stack.getName().getString().replaceAll("§.", "").toLowerCase();
                for (String target : BOOSTER_COOKIE_ITEMS) {
                    if (name.contains(target)) { hasItems = true; break; }
                }
                if (hasItems) break;
            }
        }
        if (!hasItems) {
            if (debugEnabled) mc.player.sendMessage(Text.literal("§c§lTaun+++ >> §7No booster cookie items in inventory, skipping"), false);
            return;
        }

        mc.execute(() -> { if (mc.player != null) mc.player.networkHandler.sendChatCommand("boostercookie"); });
        if (debugEnabled) mc.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Opened /boostercookie, scanning for items..."), false);

        // Wait for GUI to open
        long guiDeadline = System.currentTimeMillis() + 5000;
        while (mc.currentScreen == null && System.currentTimeMillis() < guiDeadline) Thread.sleep(100);
        if (mc.currentScreen == null) {
            LOGGER.warn("[BoosterCookie] No GUI appeared — skipping");
            return;
        }
        Thread.sleep(300); // let slots populate

        // Shift-click each target item (player inventory slots only, all stacks)
        boolean foundAny = false;
        for (String targetName : BOOSTER_COOKIE_ITEMS) {
            boolean foundMore = true;
            while (foundMore) {
                java.util.concurrent.atomic.AtomicInteger itemSlot = new java.util.concurrent.atomic.AtomicInteger(-1);
                java.util.concurrent.atomic.AtomicInteger itemCount = new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                mc.execute(() -> {
                    try {
                        if (mc.player.currentScreenHandler != null) {
                            var slots = mc.player.currentScreenHandler.slots;
                            int total = slots.size();
                            // Player inventory is always the last 36 slots (27 inv + 9 hotbar)
                            int invStart = total - 36;
                            for (int i = invStart; i < total; i++) {
                                var slot = slots.get(i);
                                if (!slot.hasStack()) continue;
                                String name = slot.getStack().getName().getString().replaceAll("§.", "").toLowerCase();
                                if (name.contains(targetName)) { itemSlot.set(i); itemCount.set(slot.getStack().getCount()); break; }
                            }
                        }
                    } finally { latch.countDown(); }
                });
                latch.await();

                if (itemSlot.get() != -1) {
                    final int s = itemSlot.get();
                    final int count = itemCount.get();
                    if (debugEnabled) mc.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Shift-clicking " + targetName + " x" + count + " at slot " + s), false);
                    for (int c = 0; c < count; c++) {
                        mc.execute(() -> {
                            if (mc.player.currentScreenHandler != null)
                                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, s, 0,
                                    net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
                        });
                        Thread.sleep(600);
                    }
                    foundAny = true;
                    Thread.sleep(400);
                } else {
                    foundMore = false;
                }
            }
        }

        if (!foundAny) {
            if (debugEnabled) mc.player.sendMessage(Text.literal("§c§lTaun+++ >> §7No booster cookie items found in GUI"), false);
        }

        // Close the GUI
        Thread.sleep(200);
        mc.execute(() -> { if (mc.currentScreen != null) mc.currentScreen.close(); });
        Thread.sleep(300);
    }

    // ── Abiphone / George sell ─────────────────────────────────────────────────
    /** Hotbar slot (1-9) holding the Abiphone. 0 = not configured. */
    private static int abiphoneSlot = 0;
    /** Cooldown to prevent the George call firing multiple times per auto-sell cycle. */
    private static long lastGeorgeCallTime = 0;
    private static final long GEORGE_CALL_COOLDOWN_MS = 30_000; // 30s between calls
    /** Set to true when we spot the first RING in chat; cleared after sell completes. */
    private static volatile boolean georgeRingDetected = false;
    private static volatile boolean georgeSlugSellActive = false; // prevents re-entry
    private static long lastGeorgeSlugSellTime = 0;
    private static final long GEORGE_SLUG_SELL_COOLDOWN_MS = 60_000; // 1 min between sells
    private static final int SLUG_SELL_THRESHOLD = 3; // trigger sell when >= this many slugs
    private static boolean georgeSlugSellEnabled = true; // toggle via /pest georgesell

    // Runtime tracking
    private static long nextRestTriggerMs = 0;
    private static long restResumeAtMs = 0;
    private static volatile boolean isResting = false;
    private static boolean eqSwapPending = false;
    private static boolean jacobContestActive = false;
    private static boolean zorroEnabled = true; // toggle via /pest eqswap zorro
    private static int jacobTimerSeconds = -1;
    private static volatile boolean cropFeverActive = false;
    private static volatile long cropFeverExpiryMs = 0;
    private static volatile String waitForChatPhrase = null;
    private static volatile boolean waitForChatMatched = false;
    private static final List<PestCdTrigger> eqPestCdTriggers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final List<PestCdTrigger> eqPestCdWdTriggers = new java.util.concurrent.CopyOnWriteArrayList<>();
    
    private static final List<PestAliveTrigger> pestAliveTriggers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static int lastPestAliveCount = -1;
    private static String lastPestAliveText = "";
    
    private static int randomDelayRange = 0;
    private static int slugCheckTickCounter = 0;
    private static final java.util.Random random = new java.util.Random();
    
    public static volatile boolean blockingInputs = false;
    
    private static boolean setupWizardActive = false;
    private static int setupStep = 0;
    private static volatile boolean setupCommandExecuted = false;
    private static final Map<String, String> setupData = new HashMap<>();
    
    private static final Map<String, Long> blockedCommands = new HashMap<>();
    
    @Override
    public void onInitializeClient() {
        LOGGER.info("Chat Command Trigger mod loaded!");
        
        configPath = Paths.get("config", MOD_ID, "triggers.txt");
        coordConfigPath = Paths.get("config", MOD_ID, "coordinates.txt");
        settingsPath = Paths.get("config", MOD_ID, "settings.properties");
        loadSettings();
        loadTriggers();
        loadCoordinateTriggers();
        TaunCommand.register();
        
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            worldJoinTime = System.currentTimeMillis();
            lastPestCooldownReady = false;
            lastPestCooldownText = "";
            lastPestAliveCount = -1;
            lastPestAliveText = "";
            isFarming = false;
            eqSwapPending = false;
            // Don't clear dynarest state if we're reconnecting as part of a planned rest cycle
            if (!dynamicRestEnabled || !isResting) {
                isResting = false;
                nextRestTriggerMs = 0;
                restResumeAtMs = 0;
            }
            jacobContestActive = false;
            jacobTimerSeconds = -1;
            cropFeverActive = false;
            cropFeverExpiryMs = 0;
            waitForChatPhrase = null;
            waitForChatMatched = false;
            georgeSlugSellActive = false;
        });
        
        net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.ALLOW_CHAT.register((message) -> {
            if (isSetupActive()) {
                handleSetupInput(message);
                return false;
            }
            return true;
        });
        
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;

            String chatText = message.getString();
            long currentTime = System.currentTimeMillis();
            String strippedText = chatText.replaceAll("§.", "");

            if (!chatTriggersEnabled) return;
            
            int receivedCount = strippedText.split("Received:", -1).length - 1;
            if (receivedCount > 3) return;
            
            if (strippedText.equals(lastMessage) && (currentTime - lastMessageTime) < COOLDOWN_MS) return;
            
            lastMessage = strippedText;
            lastMessageTime = currentTime;

            // WAITFORCHAT matching
            if (waitForChatPhrase != null && strippedText.toLowerCase().contains(waitForChatPhrase)) {
                waitForChatMatched = true;
            }

            if (strippedText.contains("(S-Shape) script activated.")) {
                isFarming = true;
                if (debugEnabled) MinecraftClient.getInstance().player.sendMessage(Text.literal("§c§lTaun+++ >> §7Farming: §atrue"), false);
                if (dynamicRestEnabled && nextRestTriggerMs == 0) {
                    int offset = restScriptingTimeOffset > 0
                        ? (new java.util.Random().nextInt(restScriptingTimeOffset * 2 + 1) - restScriptingTimeOffset)
                        : 0;
                    long farmMinutes = restScriptingTime + offset;
                    nextRestTriggerMs = System.currentTimeMillis() + farmMinutes * 60_000L;
                    MinecraftClient.getInstance().player.sendMessage(
                        Text.literal("§c§lTaun+++ >> §7Dynamic Rest: next break in §e" + farmMinutes + "m"), false);
                }
                if (eqSwapPending && eqSwapEnabled) {
                    boolean fresh = (System.currentTimeMillis() - lastEqSwapFireTime) < (3 * 60 * 1000);
                    eqSwapPending = false;
                    if (fresh) {
                        final List<PestCdTrigger> pendingTriggers = new java.util.ArrayList<>(eqPestCdTriggers);
                        new Thread(() -> {
                            try {
                                Thread.sleep(1500);
                                for (PestCdTrigger trigger : pendingTriggers) {
                                    firePestCdTrigger(trigger);
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();
                    }
                }
            } else if (strippedText.contains("(S-Shape) script stopped.")) {
                isFarming = false;
                if (debugEnabled) MinecraftClient.getInstance().player.sendMessage(Text.literal("§c§lTaun+++ >> §7Farming: §cfalse"), false);
            } else if (strippedText.contains("AutoSell script stopped. [Finished]") && !georgeSlugSellActive
                    && (System.currentTimeMillis() - lastGeorgeSlugSellTime) > GEORGE_SLUG_SELL_COOLDOWN_MS) {
                MinecraftClient mc2 = MinecraftClient.getInstance();
                int slugCount = countSlugsInInventory(mc2);
                boolean hasSlugs = georgeSlugSellEnabled && slugCount > 0;
                boolean hasBoosterItems = boosterCookieEnabled;
                if (hasSlugs || hasBoosterItems) {
                    georgeSlugSellActive = true;
                    lastGeorgeSlugSellTime = System.currentTimeMillis();
                    new Thread(() -> {
                        try {
                            if (mc2.player == null) { georgeSlugSellActive = false; return; }
                            mc2.player.sendMessage(Text.literal("§c§lTaun+++ >> §7AutoSell done, running sell sequence..."), false);
                            if (hasSlugs) triggerGeorgeSlugSell();
                            Thread.sleep(300);
                            triggerBoosterCookie();
                            mc2.execute(() -> { if (mc2.player != null) mc2.player.networkHandler.sendChatMessage(".ez-startscript netherwart:1"); });
                        } catch (Exception e) {
                            LOGGER.warn("[SlugSell] AutoSell-triggered sell failed: {}", e.getMessage());
                        } finally {
                            georgeSlugSellActive = false;
                        }
                    }, "taun-slug-sell-autosell").start();
                }
            } else if (strippedText.contains("spawned in")) {
                eqSwapPending = false;
            }

            // ── Crop Fever detection ─────────────────────────────────────────
            if (strippedText.toLowerCase().contains("caught a case of") && strippedText.toLowerCase().contains("crop fever")) {
                cropFeverActive = true;
                cropFeverExpiryMs = System.currentTimeMillis() + 60_000;
            } else if (cropFeverActive && System.currentTimeMillis() >= cropFeverExpiryMs) {
                cropFeverActive = false;
            }

            // ── Abiphone RING detection ───────────────────────────────────────
            // Flag set when George's phone starts ringing — used by triggerAbiphoneGeorgeCall
            if (!georgeRingDetected && strippedText.contains("RING")) {
                georgeRingDetected = true;
            }

            for (ChatTrigger trigger : triggers) {
                if (trigger.matches(strippedText)) {
                    String triggerKey = trigger.getGroupKey();
                    Long lastTriggered = chatTriggerCooldowns.get(triggerKey);
                    if (lastTriggered != null && (currentTime - lastTriggered) < CHAT_TRIGGER_COOLDOWN) continue;
                    chatTriggerCooldowns.put(triggerKey, currentTime);
                    
                    final String commandToExecute = trigger.getCommand();
                    final long delay = trigger.getDelayMs();
                    final List<ChatTrigger.KeybindAction> keybindActions = trigger.getKeybindActions();
                    final boolean hasKeybind = trigger.hasKeybind();
                    final boolean blockInputs = trigger.shouldBlockInputs();
                    final long blockInputsDelay = trigger.getBlockInputsDelay();
                    final boolean waitOnGui = trigger.shouldWaitOnGuiClosure();
                    final long waitOnGuiDelay = trigger.getWaitOnGuiClosureDelay();
                    final boolean waitOnGuiOpen = trigger.shouldWaitOnGuiOpen();
                    final long waitOnGuiOpenDelay = trigger.getWaitOnGuiOpenDelay();
                    final boolean useSequence = trigger.hasActions();

                    if (delay > 0) {
                        new Thread(() -> {
                            try {
                                Thread.sleep(delay);
                                if (waitOnGuiOpen) {
                                    if (!cropFeverActive) { try { waitForGuiOpenThenClose(waitOnGuiOpenDelay); } catch (GuiOpenTimeoutException e) { return; } }
                                } else if (waitOnGui) {
                                    waitForGuiClosure(waitOnGuiDelay);
                                }
                                if (useSequence) {
                                    executeActionSequence(trigger.getActions(), blockInputs, blockInputsDelay);
                                } else {
                                    if (!commandToExecute.isEmpty()) executeCommand(commandToExecute);
                                    if (trigger.hasBlockedCommands()) {
                                        for (ChatTrigger.BlockedCommand blocked : trigger.getBlockedCommands()) {
                                            activateCommandBlock(blocked.command, blocked.durationSeconds);
                                        }
                                    }
                                    if (hasKeybind) {
                                        if (blockInputs && blockInputsDelay > 0) Thread.sleep(blockInputsDelay);
                                        for (ChatTrigger.KeybindAction action : keybindActions) {
                                            Thread.sleep(applyRandomDelay(action.delayMs));
                                            pressKey(action.key, action.originalKey);
                                        }
                                    }
                                }
                                if (trigger.hasBlockedCommands()) {
                                    for (ChatTrigger.BlockedCommand blocked : trigger.getBlockedCommands()) {
                                        activateCommandBlock(blocked.command, blocked.durationSeconds);
                                    }
                                }
                            } catch (InterruptedException e) {
                                LOGGER.error("Delayed execution interrupted", e);
                            }
                        }).start();
                    } else {
                        if (waitOnGuiOpen || waitOnGui || useSequence) {
                            new Thread(() -> {
                                try {
                                    if (waitOnGuiOpen) {
                                        if (!cropFeverActive) { try { waitForGuiOpenThenClose(waitOnGuiOpenDelay); } catch (GuiOpenTimeoutException e) { return; } }
                                    } else if (waitOnGui) {
                                        waitForGuiClosure(waitOnGuiDelay);
                                    }
                                    if (useSequence) {
                                        executeActionSequence(trigger.getActions(), blockInputs, blockInputsDelay);
                                    } else {
                                        if (!commandToExecute.isEmpty()) executeCommand(commandToExecute);
                                        if (trigger.hasBlockedCommands()) {
                                            for (ChatTrigger.BlockedCommand blocked : trigger.getBlockedCommands()) {
                                                activateCommandBlock(blocked.command, blocked.durationSeconds);
                                            }
                                        }
                                        if (hasKeybind) {
                                            if (blockInputs && blockInputsDelay > 0) Thread.sleep(blockInputsDelay);
                                            if (blockInputs) blockingInputs = true;
                                            for (ChatTrigger.KeybindAction action : keybindActions) {
                                                Thread.sleep(applyRandomDelay(action.delayMs));
                                                pressKey(action.key, action.originalKey);
                                            }
                                            if (blockInputs) { Thread.sleep(50); blockingInputs = false; }
                                        }
                                    }
                                    if (trigger.hasBlockedCommands()) {
                                        for (ChatTrigger.BlockedCommand blocked : trigger.getBlockedCommands()) {
                                            activateCommandBlock(blocked.command, blocked.durationSeconds);
                                        }
                                    }
                                } catch (InterruptedException e) {
                                    if (blockInputs) blockingInputs = false;
                                    LOGGER.error("Execution interrupted", e);
                                }
                            }).start();
                        } else {
                            if (!commandToExecute.isEmpty()) executeCommand(commandToExecute);
                            if (trigger.hasBlockedCommands()) {
                                for (ChatTrigger.BlockedCommand blocked : trigger.getBlockedCommands()) {
                                    activateCommandBlock(blocked.command, blocked.durationSeconds);
                                }
                            }
                            if (hasKeybind) {
                                new Thread(() -> {
                                    try {
                                        if (blockInputs && blockInputsDelay > 0) Thread.sleep(blockInputsDelay);
                                        if (blockInputs) blockingInputs = true;
                                        for (ChatTrigger.KeybindAction action : keybindActions) {
                                            Thread.sleep(applyRandomDelay(action.delayMs));
                                            pressKey(action.key, action.originalKey);
                                        }
                                        if (blockInputs) { Thread.sleep(50); blockingInputs = false; }
                                    } catch (InterruptedException e) {
                                        if (blockInputs) blockingInputs = false;
                                        LOGGER.error("Keybind press interrupted", e);
                                    }
                                }).start();
                            }
                        }
                    }
                }
            }
        });
        
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Capture etherwarp coords on right-click during /pest setetherwarp
            if (settingEtherwarpCoords && client.player != null && client.options.useKey.isPressed()) {
                captureEtherwarpCoordsIfPending();
            }
            if (client.player != null && !coordinateTriggers.isEmpty() && coordTriggersEnabled) {
                checkCoordinates(client.player);
            }
            if (client.player != null && chatTriggersEnabled) {
                checkPestCooldown(client);
                checkPestAlive(client);
            }
            if (client.player != null && isFarming && !georgeSlugSellActive) {
                slugCheckTickCounter++;
                if (slugCheckTickCounter >= 100) {
                    slugCheckTickCounter = 0;
                    if ((System.currentTimeMillis() - lastGeorgeSlugSellTime) > GEORGE_SLUG_SELL_COOLDOWN_MS) {
                        checkSlugInventory(client);
                    }
                }
            }

            // ── Dynamic Rest: check if it's time to take a break ──────────────
            if (client.player != null && dynamicRestEnabled
                    && !isResting && nextRestTriggerMs > 0
                    && System.currentTimeMillis() >= nextRestTriggerMs) {

                isResting = true;
                nextRestTriggerMs = 0;

                long breakMinutes = Math.max(1, restBreakTime);
                restResumeAtMs = System.currentTimeMillis() + breakMinutes * 60_000L;

                // ── DISCONNECT MODE ──────────────────────────────────────
                // 1. Stop the script (only if farming)
                // 2. Send /setspawn (only if farming)
                // 3. Disconnect from the server
                // 4. Sleep out the break offline
                // 5. Reconnect and run /play sb


                final long resumeAt = restResumeAtMs;
                final String savedServerAddress = client.getCurrentServerEntry() != null ? client.getCurrentServerEntry().address : null;
                final String savedServerName = client.getCurrentServerEntry() != null ? client.getCurrentServerEntry().name : null;
                final boolean wasFarming = isFarming;
                new Thread(() -> {
                    try {
                        MinecraftClient mc = MinecraftClient.getInstance();

                        // Step 1 — .ez-stopscript (only if farming)
                        if (wasFarming) {
                            mc.execute(() -> {
                                if (mc.player != null) mc.player.networkHandler.sendChatMessage(".ez-stopscript");
                            });
                            Thread.sleep(1000);
                        }

                        // Step 2 — /setspawn (only if farming)
                        if (wasFarming) {
                            mc.execute(() -> {
                                if (mc.player != null) mc.player.networkHandler.sendChatCommand("setspawn");
                            });
                            Thread.sleep(4000); // give setspawn time to reach server
                        }

                        // Step 3 — disconnect
                        mc.execute(() -> {
                            if (mc.player != null) {
                                mc.player.networkHandler.getConnection().disconnect(
                                    Text.literal("[Taun+++] Dynamic Rest — reconnecting in " + breakMinutes + "m"));
                            }
                        });

                        // Step 4 — sleep out the break
                        while (System.currentTimeMillis() < resumeAt) {
                            Thread.sleep(5000);
                            if (!isResting) return; // manually cancelled
                        }

                        // Step 5 — reconnect
                        if (savedServerAddress == null) {
                            LOGGER.warn("[DynRest/disconnect] Could not reconnect — no saved server info.");
                        } else {
                            mc.execute(() -> {
                                TitleScreen title = new TitleScreen();
                                mc.setScreen(title);
                                ServerInfo entry = new ServerInfo(savedServerName, savedServerAddress, ServerInfo.ServerType.OTHER);
                                ConnectScreen.connect(title, mc, ServerAddress.parse(savedServerAddress), entry, false, null);
                                LOGGER.info("[DynRest/disconnect] Connecting to {}...", savedServerAddress);
                            });

                            // Step 6 — wait until player is in the lobby (up to 60s)
                            long joinDeadline = System.currentTimeMillis() + 60_000;
                            while (mc.player == null && System.currentTimeMillis() < joinDeadline) {
                                Thread.sleep(1000);
                            }
                            if (mc.player == null) {
                                LOGGER.warn("[DynRest/disconnect] Player never joined after 60s — giving up.");
                            } else {
                                Thread.sleep(3000); // let lobby finish loading
                                mc.execute(() -> {
                                    if (mc.player != null) mc.player.networkHandler.sendChatCommand("play sb");
                                });
                                LOGGER.info("[DynRest/disconnect] Sent /play sb, waiting 10s for SkyBlock to load...");
                                Thread.sleep(10000);

                                mc.execute(() -> {
                                    if (mc.player != null) {
                                        int offset = restScriptingTimeOffset > 0
                                            ? (new java.util.Random().nextInt(restScriptingTimeOffset * 2 + 1) - restScriptingTimeOffset)
                                            : 0;
                                        long farmMinutes = restScriptingTime + offset;
                                        nextRestTriggerMs = System.currentTimeMillis() + farmMinutes * 60_000L;
                                        isResting = false;
                                        saveSettings();
                                        LOGGER.info("[DynRest/disconnect] In SkyBlock. Next rest in {}m.", farmMinutes);
                                        mc.player.networkHandler.sendChatMessage(".ez-startscript netherwart:1");
                                        LOGGER.info("[DynRest/disconnect] Started netherwart:1.");
                                    }
                                });
                            }
                        }

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "taun-dynrest-disconnect").start();
            }

        });
    }
    
    // ── Dynamic Rest public API ────────────────────────────────────────────────

    public static void enableDynamicRest(boolean enable) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        dynamicRestEnabled = enable;
        if (!dynamicRestEnabled) {
            isResting = false;
            nextRestTriggerMs = 0;
            restResumeAtMs = 0;
        } else if (nextRestTriggerMs == 0) {
            // Start the timer immediately even if not farming yet
            int offset = restScriptingTimeOffset > 0
                ? (new java.util.Random().nextInt(restScriptingTimeOffset * 2 + 1) - restScriptingTimeOffset)
                : 0;
            long farmMinutes = restScriptingTime + offset;
            nextRestTriggerMs = System.currentTimeMillis() + farmMinutes * 60_000L;
            client.player.sendMessage(
                Text.literal("§c§lTaun+++ >> §7Dynamic Rest: next break in §e" + farmMinutes + "m"), false);
        }
        saveSettings();
        client.player.sendMessage(
            Text.literal("§c§lTaun+++ >> §7Dynamic Rest: " + (dynamicRestEnabled ? "§aENABLED" : "§cDISABLED")), false);
    }

    public static void toggleDynamicRest() {
        enableDynamicRest(!dynamicRestEnabled);
    }

    /** Sets scripting time from a flexible string like "2h", "2.5 hours", "90m", "90". */
    public static void setDynRestScriptingTimeFromString(String input) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        int minutes = parseDynaRestTime(input);
        if (minutes < 1) {
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §cInvalid time format. Try: §e2h§c, §e2.5h§c, §e2.5 hours§c, §e90m§c, §e90"), false);
            return;
        }
        setDynRestScriptingTime(minutes);
        if (!dynamicRestEnabled) {
            enableDynamicRest(true);
        }
    }

    public static void setDynRestScriptingTime(int minutes) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (minutes < 1) { client.player.sendMessage(Text.literal("§c§lTaun+++ >> §cScripting time must be >= 1 minute."), false); return; }
        restScriptingTime = minutes;
        saveSettings();
        client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Dynamic Rest scripting time set to §e" + minutes + "m"), false);
    }

    public static void setDynRestScriptingOffset(int minutes) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (minutes < 0) { client.player.sendMessage(Text.literal("§c§lTaun+++ >> §cOffset must be >= 0."), false); return; }
        restScriptingTimeOffset = minutes;
        saveSettings();
        client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Dynamic Rest scripting offset set to §e±" + minutes + "m"), false);
    }

    public static void setDynRestBreakTime(int minutes) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (minutes < 1) { client.player.sendMessage(Text.literal("§c§lTaun+++ >> §cBreak time must be >= 1 minute."), false); return; }
        restBreakTime = minutes;
        saveSettings();
        client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Dynamic Rest break time set to §e" + minutes + "m"), false);
    }

    public static void showDynRestStatus() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Dynamic Rest Status:"), false);
        client.player.sendMessage(Text.literal("  §7Enabled:     " + (dynamicRestEnabled ? "§ayes" : "§cno")), false);
        client.player.sendMessage(Text.literal("  §7Script time: §e" + restScriptingTime + "m §7± §e" + restScriptingTimeOffset + "m"), false);
        client.player.sendMessage(Text.literal("  §7Break time:  §e" + restBreakTime + "m"), false);
        if (isResting && restResumeAtMs > 0) {
            long remSec = Math.max(0, (restResumeAtMs - System.currentTimeMillis()) / 1000);
            client.player.sendMessage(Text.literal("  §7Currently resting. Resuming in §e" + (remSec / 60) + "m " + (remSec % 60) + "s"), false);
        } else if (dynamicRestEnabled && nextRestTriggerMs > 0) {
            long remSec = Math.max(0, (nextRestTriggerMs - System.currentTimeMillis()) / 1000);
            client.player.sendMessage(Text.literal("  §7Next rest in §e" + (remSec / 60) + "m " + (remSec % 60) + "s"), false);
        } else {
            client.player.sendMessage(Text.literal("  §7(timer not active — enable dynarest to begin)"), false);
        }
    }

    // ── Settings save/load ────────────────────────────────────────────────────

    private static void loadSettings() {
        try {
            if (!Files.exists(settingsPath)) {
                LOGGER.info("No settings.properties found, using defaults");
                return;
            }
            Properties props = new Properties();
            props.load(Files.newBufferedReader(settingsPath));
            chatTriggersEnabled = Boolean.parseBoolean(props.getProperty("chatTriggersEnabled", "true"));
            coordTriggersEnabled = Boolean.parseBoolean(props.getProperty("coordTriggersEnabled", "true"));
            rodswapEnabled = Boolean.parseBoolean(props.getProperty("rodswapEnabled", "false"));
            rosedragEnabled = Boolean.parseBoolean(props.getProperty("rosedragEnabled", "false"));
            wardrobeSwapEnabled = Boolean.parseBoolean(props.getProperty("wardrobeSwapEnabled", "false"));
            etherwarpEnabled = Boolean.parseBoolean(props.getProperty("etherwarpEnabled", "false"));
            etherwarpYaw = Float.parseFloat(props.getProperty("etherwarpYaw", "-95"));
            etherwarpPitch = Float.parseFloat(props.getProperty("etherwarpPitch", "-70"));
            eqSwapEnabled = Boolean.parseBoolean(props.getProperty("eqSwapEnabled", "false"));
            zorroEnabled = Boolean.parseBoolean(props.getProperty("zorroEnabled", "true"));
            finneganMode = Boolean.parseBoolean(props.getProperty("finneganMode", "false"));
            rotateSpeedMs = Long.parseLong(props.getProperty("rotateSpeedMs", "250"));
            randomDelayRange = Integer.parseInt(props.getProperty("randomDelayRange", "0"));
            dynamicRestEnabled = Boolean.parseBoolean(props.getProperty("dynamicRestEnabled", "false"));
            restScriptingTime = Integer.parseInt(props.getProperty("restScriptingTime", "30"));
            restScriptingTimeOffset = Integer.parseInt(props.getProperty("restScriptingTimeOffset", "3"));
            restBreakTime = Integer.parseInt(props.getProperty("restBreakTime", "10"));            abiphoneSlot = Integer.parseInt(props.getProperty("abiphoneSlot", "0"));
            georgeSlugSellEnabled = Boolean.parseBoolean(props.getProperty("georgeSlugSellEnabled", "true"));
            long savedTriggerMs = Long.parseLong(props.getProperty("nextRestTriggerMs", "0"));
            nextRestTriggerMs = (savedTriggerMs > System.currentTimeMillis()) ? savedTriggerMs : 0;
            if (rodswapEnabled && wardrobeSwapEnabled) {
                wardrobeSwapEnabled = false;
                LOGGER.warn("loadSettings: both rodswap and wdswap were true - disabling wdswap");
            }
            int storedVersion = Integer.parseInt(props.getProperty("configVersion", "0"));
            if (storedVersion < CONFIG_VERSION) {
                LOGGER.info("Config version outdated ({} -> {}), rewriting triggers.txt", storedVersion, CONFIG_VERSION);
                String mode = rodswapEnabled ? "rodswap" : wardrobeSwapEnabled ? "wdswap" : "none";
                writePestTriggers(mode);
                saveSettings();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load settings", e);
        }
    }

    public static void saveSettings() {
        try {
            Files.createDirectories(settingsPath.getParent());
            Properties props = new Properties();
            props.setProperty("chatTriggersEnabled", String.valueOf(chatTriggersEnabled));
            props.setProperty("coordTriggersEnabled", String.valueOf(coordTriggersEnabled));
            props.setProperty("rodswapEnabled", String.valueOf(rodswapEnabled));
            props.setProperty("rosedragEnabled", String.valueOf(rosedragEnabled));
            props.setProperty("wardrobeSwapEnabled", String.valueOf(wardrobeSwapEnabled));
            props.setProperty("etherwarpEnabled", String.valueOf(etherwarpEnabled));
            props.setProperty("etherwarpYaw", String.valueOf(etherwarpYaw));
            props.setProperty("etherwarpPitch", String.valueOf(etherwarpPitch));
            props.setProperty("eqSwapEnabled", String.valueOf(eqSwapEnabled));
            props.setProperty("zorroEnabled", String.valueOf(zorroEnabled));
            props.setProperty("finneganMode", String.valueOf(finneganMode));
            props.setProperty("rotateSpeedMs", String.valueOf(rotateSpeedMs));
            props.setProperty("randomDelayRange", String.valueOf(randomDelayRange));
            props.setProperty("dynamicRestEnabled", String.valueOf(dynamicRestEnabled));
            props.setProperty("restScriptingTime", String.valueOf(restScriptingTime));
            props.setProperty("restScriptingTimeOffset", String.valueOf(restScriptingTimeOffset));
            props.setProperty("restBreakTime", String.valueOf(restBreakTime));
            props.setProperty("nextRestTriggerMs", String.valueOf(nextRestTriggerMs));
            props.setProperty("abiphoneSlot", String.valueOf(abiphoneSlot));
            props.setProperty("georgeSlugSellEnabled", String.valueOf(georgeSlugSellEnabled));
            props.setProperty("configVersion", String.valueOf(CONFIG_VERSION));
            props.store(Files.newBufferedWriter(settingsPath), "Taun+++ Settings - auto-generated");
            LOGGER.info("Settings saved");
        } catch (IOException e) {
            LOGGER.error("Failed to save settings", e);
        }
    }

    // ── Everything below is unchanged from the original ───────────────────────

    private static void loadTriggers() {
        try {
            Files.createDirectories(configPath.getParent());
            if (!Files.exists(configPath)) createDefaultConfig();
            List<String> lines = Files.readAllLines(configPath);
            triggers.clear();
            pestCdTriggers.clear();
            eqPestCdTriggers.clear();
            eqPestCdWdTriggers.clear();
            pestAliveTriggers.clear();
            Map<String, String> variables = new HashMap<>();
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("@")) {
                    String[] parts = line.substring(1).split("=", 2);
                    if (parts.length == 2) variables.put("@" + parts[0].trim(), parts[1].trim());
                }
            }
            int i = 0;
            while (i < lines.size()) {
                String line = lines.get(i).trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("@")) { i++; continue; }
                for (Map.Entry<String, String> var : variables.entrySet()) line = line.replace(var.getKey(), var.getValue());
                if (line.startsWith("TRIGGER:")) { i = parseNewFormatTrigger(lines, i, variables); continue; }
                if (line.equals("PestCD:") || line.startsWith("PestCD:")) { i = parsePestCdTrigger(lines, i, variables); continue; }
                if (line.equals("EQPestCD:") || line.startsWith("EQPestCD:")) { i = parseEqPestCdTrigger(lines, i, variables); continue; }
                if (line.equals("EQPestCDWD:") || line.startsWith("EQPestCDWD:")) { i = parseEqPestCdWdTrigger(lines, i, variables); continue; }
                if (line.equals("EQPestCDFIN:") || line.startsWith("EQPestCDFIN:")) { i++; continue; } // removed feature, skip
                if (line.matches("PestAlive\\d+:.*")) { i = parsePestAliveTrigger(lines, i, variables, line); continue; }
                i = parseOldFormatTrigger(lines, i, line);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load triggers", e);
        }
    }

    private static boolean parseActionLine(String line, String originalLine,
                                           List<ChatTrigger.TriggerAction> actionList,
                                           List<ChatTrigger.KeybindAction> keybindActions,
                                           Object[] out) {
        String command = (String) out[0];
        long commandDelay = (Long) out[1];
        boolean blockInputs = (Boolean) out[2];
        long blockInputsDelay = (Long) out[3];

        if (line.startsWith("COMMAND:")) {
            String commandStr = line.substring("COMMAND:".length()).trim();
            String[] parts = commandStr.split("\\s+after\\s+", 2);
            if (parts.length == 2) {
                String newCommand = parts[0].trim();
                long delay = parseTime(parts[1].trim());
                commandDelay = delay;
                command = command.isEmpty() ? newCommand : command + "; " + newCommand;
                actionList.add(new ChatTrigger.TriggerAction(ChatTrigger.TriggerAction.Type.COMMAND, newCommand, newCommand, delay));
            } else {
                String newCommand = commandStr;
                command = command.isEmpty() ? newCommand : command + "; " + newCommand;
                actionList.add(new ChatTrigger.TriggerAction(ChatTrigger.TriggerAction.Type.COMMAND, newCommand, newCommand, 0));
            }
        } else if (line.startsWith("PRESS:")) {
            String pressStr = line.substring("PRESS:".length()).trim();
            String originalPressStr = originalLine.substring("PRESS:".length()).trim();
            String[] parts = pressStr.split("\\s+after\\s+", 2);
            String[] originalParts = originalPressStr.split("\\s+after\\s+", 2);
            long delay = parts.length == 2 ? parseTime(parts[1].trim()) : 0;
            String key = parts[0].trim();
            String originalKeyName = originalParts[0].trim();
            if (!key.isEmpty()) {
                keybindActions.add(new ChatTrigger.KeybindAction(delay, key, originalKeyName));
                actionList.add(new ChatTrigger.TriggerAction(ChatTrigger.TriggerAction.Type.PRESS, key, originalKeyName, delay));
            }
        } else if (line.startsWith("HOLD:")) {
            String holdStr = line.substring("HOLD:".length()).trim();
            String originalHoldStr = originalLine.substring("HOLD:".length()).trim();
            String[] afterParts = holdStr.split("\\s+after\\s+", 2);
            String[] originalAfterParts = originalHoldStr.split("\\s+after\\s+", 2);
            long delay = afterParts.length == 2 ? parseTime(afterParts[1].trim()) : 0;
            String holdBody = afterParts[0].trim();
            String originalHoldBody = originalAfterParts[0].trim();
            String[] forParts = holdBody.split("\\s+for\\s+", 2);
            String[] originalForParts = originalHoldBody.split("\\s+for\\s+", 2);
            String key = forParts[0].trim();
            String originalKeyName = originalForParts[0].trim();
            if (!key.isEmpty()) {
                actionList.add(new ChatTrigger.TriggerAction(ChatTrigger.TriggerAction.Type.HOLD, key, originalKeyName, delay));
                if (forParts.length == 2) {
                    long holdDuration = parseTime(forParts[1].trim());
                    actionList.add(new ChatTrigger.TriggerAction(ChatTrigger.TriggerAction.Type.UNHOLD, key, originalKeyName, holdDuration));
                }
            }
        } else if (line.startsWith("UNHOLD:")) {
            String unholdStr = line.substring("UNHOLD:".length()).trim();
            String originalUnholdStr = originalLine.substring("UNHOLD:".length()).trim();
            String[] parts = unholdStr.split("\\s+after\\s+", 2);
            String[] originalParts = originalUnholdStr.split("\\s+after\\s+", 2);
            long delay = parts.length == 2 ? parseTime(parts[1].trim()) : 0;
            String key = parts[0].trim();
            String originalKeyName = originalParts[0].trim();
            if (!key.isEmpty()) actionList.add(new ChatTrigger.TriggerAction(ChatTrigger.TriggerAction.Type.UNHOLD, key, originalKeyName, delay));
        } else if (line.startsWith("ROTATE_TO:")) {
            String rotStr = line.substring("ROTATE_TO:".length()).trim();
            long rotDelay = 0; long rotateDuration = 250;
            String[] afterParts = rotStr.split("\\s+after\\s+", 2);
            if (afterParts.length == 2) {
                String[] overInAfter = afterParts[1].trim().split("\\s+over\\s+", 2);
                rotDelay = parseTime(overInAfter[0].trim());
                if (overInAfter.length == 2) rotateDuration = parseTime(overInAfter[1].trim());
                rotStr = afterParts[0].trim();
            }
            String[] overParts = rotStr.split("\\s+over\\s+", 2);
            if (overParts.length == 2) { rotateDuration = parseTime(overParts[1].trim()); rotStr = overParts[0].trim(); }
            String[] commaParts = rotStr.split(",", 2);
            if (commaParts.length == 2) {
                try {
                    float[] yaw = parseRangeWithSpaces(commaParts[0].trim());
                    float[] pitch = parseRangeWithSpaces(commaParts[1].trim());
                    actionList.add(new ChatTrigger.TriggerAction(yaw[0], yaw[1], pitch[0], pitch[1], rotDelay, rotateDuration));
                } catch (Exception e) { LOGGER.warn("Could not parse ROTATE_TO: '{}'", line); }
            }
        } else if (line.startsWith("ETHERWARP_TO:")) {
            String ewStr = line.substring("ETHERWARP_TO:".length()).trim();
            long ewDelay = 0;
            String[] ewAfterParts = ewStr.split("\\s+after\\s+", 2);
            if (ewAfterParts.length == 2) { ewDelay = parseTime(ewAfterParts[1].trim()); ewStr = ewAfterParts[0].trim(); }
            String[] ewCommaParts = ewStr.split(",", 2);
            if (ewCommaParts.length == 2) {
                try {
                    float[] yaw = parseRangeWithSpaces(ewCommaParts[0].trim());
                    float[] pitch = parseRangeWithSpaces(ewCommaParts[1].trim());
                    actionList.add(new ChatTrigger.TriggerAction(yaw[0], yaw[1], pitch[0], pitch[1], -10f, -30f, ewDelay));
                } catch (Exception e) { LOGGER.warn("[ParseActionLine] Could not parse ETHERWARP_TO: '{}'", line); }
            }
        } else if (line.equals("RODSWAP")) {
            actionList.add(new ChatTrigger.TriggerAction(ChatTrigger.TriggerAction.Type.RODSWAP, "", "", 0));
        } else if (line.startsWith("EQSWAP:")) {
            String swapStr = line.substring("EQSWAP:".length()).trim();
            long swapDelay = 0;
            String[] swapAfterParts = swapStr.split("\\s+after\\s+", 2);
            if (swapAfterParts.length == 2) { swapDelay = parseTime(swapAfterParts[1].trim()); swapStr = swapAfterParts[0].trim(); }
            String target = swapStr.toUpperCase().replace("/", "_");
            if (target.equals("PEST") || target.equals("BLOSSOM_LOTUS")) {
                actionList.add(new ChatTrigger.TriggerAction(target, swapDelay));
            }
        } else if (line.equals("IFJACOB=FALSE")) {
            actionList.add(new ChatTrigger.TriggerAction(ChatTrigger.TriggerAction.Type.IFJACOB_FALSE, "", "", 0));
        } else if (line.equals("IFJACOB=TRUE")) {
            actionList.add(new ChatTrigger.TriggerAction(ChatTrigger.TriggerAction.Type.IFJACOB_TRUE, "", "", 0));
        } else if (line.startsWith("WAITFORCHAT:")) {
            String phrase = line.substring("WAITFORCHAT:".length()).trim().replaceAll("^\"|\"$", "");
            actionList.add(new ChatTrigger.TriggerAction(ChatTrigger.TriggerAction.Type.WAITFORCHAT, phrase, "", 0));
        } else if (line.startsWith("WAITFORJACOBTIMER:")) {
            String secStr = line.substring("WAITFORJACOBTIMER:".length()).trim();
            int threshold = 5;
            try { threshold = Integer.parseInt(secStr); } catch (NumberFormatException ignored) {}
            actionList.add(new ChatTrigger.TriggerAction(ChatTrigger.TriggerAction.Type.WAITFORJACOBTIMER, String.valueOf(threshold), "", 0));
        } else if (line.startsWith("IFCROPFEVER:")) {
            String val = line.substring("IFCROPFEVER:".length()).trim().toUpperCase();
            if (val.equals("SKIP_GUI")) actionList.add(new ChatTrigger.TriggerAction(ChatTrigger.TriggerAction.Type.IFCROPFEVER_SKIP_GUI, "", "", 0));
        } else if (line.equals("RETRYUNTILSKYBLOCK")) {
            actionList.add(new ChatTrigger.TriggerAction(ChatTrigger.TriggerAction.Type.RETRYUNTILSKYBLOCK, "", "", 0));
        } else if (line.startsWith("WAITONGUIOPEN")) {
            long preDelay = 0;
            if (line.startsWith("WAITONGUIOPEN after")) preDelay = parseTime(line.substring("WAITONGUIOPEN after".length()).trim());
            actionList.add(new ChatTrigger.TriggerAction(ChatTrigger.TriggerAction.Type.WAITONGUIOPEN, "", "", preDelay));
        } else {
            out[0] = command; out[1] = commandDelay; out[2] = blockInputs; out[3] = blockInputsDelay;
            return false;
        }
        out[0] = command; out[1] = commandDelay; out[2] = blockInputs; out[3] = blockInputsDelay;
        return true;
    }

    private static int parseNewFormatTrigger(List<String> lines, int startIndex, Map<String, String> variables) {
        String triggerLine = lines.get(startIndex).trim();
        String rawTriggerText = triggerLine.substring("TRIGGER:".length()).trim();
        // Support multiple trigger phrases: TRIGGER: "phrase one" or "phrase two"
        // Split on literal  or  between quoted phrases and collect all phrases
        String[] triggerPhrases = rawTriggerText.split("\"\\s+or\\s+\"");
        // Strip leading/trailing quotes from each phrase
        for (int pi = 0; pi < triggerPhrases.length; pi++) {
            triggerPhrases[pi] = triggerPhrases[pi].replaceAll("^\"|\"$", "").trim();
        }
        String triggerText = triggerPhrases[0]; // primary phrase used for the trigger object
        if (triggerText == null || triggerText.trim().isEmpty()) {
            int i = startIndex + 1;
            while (i < lines.size()) {
                String line = lines.get(i).trim();
                if (line.isEmpty() || line.startsWith("TRIGGER:") || line.startsWith("#") || line.startsWith("@")) return i;
                i++;
            }
            return i;
        }
        String command = ""; long commandDelay = 0;
        List<ChatTrigger.KeybindAction> keybindActions = new ArrayList<>();
        List<ChatTrigger.TriggerAction> actionList = new ArrayList<>();
        boolean blockInputs = false; long blockInputsDelay = 0;
        List<ChatTrigger.BlockedCommand> blockedCommands = new ArrayList<>();
        boolean waitOnGuiClosure = false; long waitOnGuiClosureDelay = 0; boolean waitOnGuiOpen = false; long waitOnGuiOpenDelay = 0;
        int i = startIndex + 1;
        while (i < lines.size()) {
            String originalLine = lines.get(i).trim();
            String line = originalLine;
            for (Map.Entry<String, String> var : variables.entrySet()) line = line.replace(var.getKey(), var.getValue());
            if (line.isEmpty() || line.startsWith("TRIGGER:") || line.startsWith("#") || line.startsWith("@")) break;
            Object[] out = new Object[]{command, commandDelay, blockInputs, blockInputsDelay};
            if (parseActionLine(line, originalLine, actionList, keybindActions, out)) {
                command = (String) out[0]; commandDelay = (Long) out[1]; blockInputs = (Boolean) out[2]; blockInputsDelay = (Long) out[3];
            } else if (line.startsWith("DELAY:")) {
                commandDelay = parseTime(line.substring("DELAY:".length()).trim());
            } else if (line.startsWith("WAITONGUICLOSURE")) {
                if (line.startsWith("WAITONGUICLOSURE after")) waitOnGuiClosureDelay = parseTime(line.substring("WAITONGUICLOSURE after".length()).trim());
                waitOnGuiClosure = true;
            } else if (line.startsWith("WAITONGUIOPEN")) {
                if (line.startsWith("WAITONGUIOPEN after")) waitOnGuiOpenDelay = parseTime(line.substring("WAITONGUIOPEN after".length()).trim());
                waitOnGuiOpen = true;
            } else if (line.startsWith("BLOCK:")) {
                String blockStr = line.substring("BLOCK:".length()).trim();
                String[] parts = blockStr.split("\\s+for\\s+", 2);
                if (parts.length == 2) {
                    String cmdToBlock = parts[0].trim();
                    if (cmdToBlock.startsWith("/")) cmdToBlock = cmdToBlock.substring(1);
                    blockedCommands.add(new ChatTrigger.BlockedCommand(cmdToBlock, (int)(parseTime(parts[1].trim()) / 1000)));
                }
            }
            i++;
        }
        if (command.isEmpty() && keybindActions.isEmpty() && actionList.isEmpty()) return i;
        // Register one trigger per phrase (supports TRIGGER: "a" or "b" or "c")
        for (String phrase : triggerPhrases) {
            if (phrase == null || phrase.isEmpty()) continue;
            try {
                ChatTrigger t = new ChatTrigger(phrase, rawTriggerText, command, commandDelay, keybindActions, blockInputs, blockInputsDelay, blockedCommands, waitOnGuiClosure, waitOnGuiClosureDelay, waitOnGuiOpen, waitOnGuiOpenDelay);
                t.actions.addAll(actionList);
                triggers.add(t);
            } catch (IllegalArgumentException e) { LOGGER.error("Failed to create trigger '{}' on line {}: {}", phrase, startIndex + 1, e.getMessage()); }
        }
        return i;
    }

    private static int parsePestCdTrigger(List<String> lines, int startIndex, Map<String, String> variables) {
        String command = ""; long commandDelay = 0;
        List<ChatTrigger.KeybindAction> keybindActions = new ArrayList<>();
        List<ChatTrigger.TriggerAction> actionList = new ArrayList<>();
        boolean blockInputs = false; long blockInputsDelay = 0;
        List<ChatTrigger.BlockedCommand> blockedCommands = new ArrayList<>();
        int i = startIndex + 1;
        while (i < lines.size()) {
            String originalLine = lines.get(i).trim(); String line = originalLine;
            for (Map.Entry<String, String> var : variables.entrySet()) line = line.replace(var.getKey(), var.getValue());
            if (line.isEmpty() || line.startsWith("TRIGGER:") || line.startsWith("PestCD:") || line.startsWith("#") || line.startsWith("@")) break;
            if (line.startsWith("DELAY:")) { commandDelay = parseTime(line.substring("DELAY:".length()).trim()); }
            else if (line.startsWith("BLOCK:")) {
                String[] parts = line.substring("BLOCK:".length()).trim().split("\\s+for\\s+", 2);
                if (parts.length == 2) { String cmd = parts[0].trim(); if (cmd.startsWith("/")) cmd = cmd.substring(1); blockedCommands.add(new ChatTrigger.BlockedCommand(cmd, (int)(parseTime(parts[1].trim()) / 1000))); }
            } else {
                Object[] out = new Object[]{command, commandDelay, blockInputs, blockInputsDelay};
                if (parseActionLine(line, originalLine, actionList, keybindActions, out)) { command = (String) out[0]; commandDelay = (Long) out[1]; blockInputs = (Boolean) out[2]; blockInputsDelay = (Long) out[3]; }
            }
            i++;
        }
        if (!command.isEmpty() || !keybindActions.isEmpty()) pestCdTriggers.add(new PestCdTrigger(command, commandDelay, keybindActions, blockInputs, blockInputsDelay, blockedCommands, actionList));
        return i;
    }

    private static int parseEqPestCdTrigger(List<String> lines, int startIndex, Map<String, String> variables) {
        String command = ""; long commandDelay = 0;
        List<ChatTrigger.KeybindAction> keybindActions = new ArrayList<>();
        List<ChatTrigger.TriggerAction> actionList = new ArrayList<>();
        boolean blockInputs = false; long blockInputsDelay = 0;
        List<ChatTrigger.BlockedCommand> blockedCommands = new ArrayList<>();
        int i = startIndex + 1;
        while (i < lines.size()) {
            String originalLine = lines.get(i).trim(); String line = originalLine;
            for (Map.Entry<String, String> var : variables.entrySet()) line = line.replace(var.getKey(), var.getValue());
            if (line.isEmpty() || line.startsWith("TRIGGER:") || line.startsWith("PestCD:") || line.startsWith("EQPestCD:") || line.startsWith("EQPestCDWD:") || line.startsWith("EQPestCDFIN:") || line.startsWith("#") || line.startsWith("@")) break;
            if (line.startsWith("DELAY:")) { commandDelay = parseTime(line.substring("DELAY:".length()).trim()); }
            else if (line.startsWith("BLOCK:")) {
                String[] parts = line.substring("BLOCK:".length()).trim().split("\\s+for\\s+", 2);
                if (parts.length == 2) { String cmd = parts[0].trim(); if (cmd.startsWith("/")) cmd = cmd.substring(1); blockedCommands.add(new ChatTrigger.BlockedCommand(cmd, (int)(parseTime(parts[1].trim()) / 1000))); }
            } else {
                Object[] out = new Object[]{command, commandDelay, blockInputs, blockInputsDelay};
                if (parseActionLine(line, originalLine, actionList, keybindActions, out)) { command = (String) out[0]; commandDelay = (Long) out[1]; blockInputs = (Boolean) out[2]; blockInputsDelay = (Long) out[3]; }
            }
            i++;
        }
        if (!command.isEmpty() || !keybindActions.isEmpty() || !actionList.isEmpty()) eqPestCdTriggers.add(new PestCdTrigger(command, commandDelay, keybindActions, blockInputs, blockInputsDelay, blockedCommands, actionList));
        return i;
    }

    private static int parseEqPestCdWdTrigger(List<String> lines, int startIndex, Map<String, String> variables) {
        String command = ""; long commandDelay = 0;
        List<ChatTrigger.KeybindAction> keybindActions = new ArrayList<>();
        List<ChatTrigger.TriggerAction> actionList = new ArrayList<>();
        boolean blockInputs = false; long blockInputsDelay = 0;
        List<ChatTrigger.BlockedCommand> blockedCommands = new ArrayList<>();
        int i = startIndex + 1;
        while (i < lines.size()) {
            String originalLine = lines.get(i).trim(); String line = originalLine;
            for (Map.Entry<String, String> var : variables.entrySet()) line = line.replace(var.getKey(), var.getValue());
            if (line.isEmpty() || line.startsWith("TRIGGER:") || line.startsWith("PestCD:") || line.startsWith("EQPestCD:") || line.startsWith("EQPestCDWD:") || line.startsWith("EQPestCDFIN:") || line.startsWith("#") || line.startsWith("@")) break;
            if (line.startsWith("DELAY:")) { commandDelay = parseTime(line.substring("DELAY:".length()).trim()); }
            else if (line.startsWith("BLOCK:")) {
                String[] parts = line.substring("BLOCK:".length()).trim().split("\\s+for\\s+", 2);
                if (parts.length == 2) { String cmd = parts[0].trim(); if (cmd.startsWith("/")) cmd = cmd.substring(1); blockedCommands.add(new ChatTrigger.BlockedCommand(cmd, (int)(parseTime(parts[1].trim()) / 1000))); }
            } else {
                Object[] out = new Object[]{command, commandDelay, blockInputs, blockInputsDelay};
                if (parseActionLine(line, originalLine, actionList, keybindActions, out)) { command = (String) out[0]; commandDelay = (Long) out[1]; blockInputs = (Boolean) out[2]; blockInputsDelay = (Long) out[3]; }
            }
            i++;
        }
        if (!command.isEmpty() || !keybindActions.isEmpty() || !actionList.isEmpty()) eqPestCdWdTriggers.add(new PestCdTrigger(command, commandDelay, keybindActions, blockInputs, blockInputsDelay, blockedCommands, actionList));
        return i;
    }

    private static int parsePestAliveTrigger(List<String> lines, int startIndex, Map<String, String> variables, String headerLine) {
        int threshold = 1;
        try { threshold = Integer.parseInt(headerLine.replaceAll("(?i)PestAlive(\\d+):.*", "$1")); } catch (NumberFormatException e) {}
        String command = ""; long commandDelay = 0;
        List<ChatTrigger.KeybindAction> keybindActions = new ArrayList<>();
        List<ChatTrigger.TriggerAction> actionList = new ArrayList<>();
        boolean blockInputs = false; long blockInputsDelay = 0;
        List<ChatTrigger.BlockedCommand> blockedCommands = new ArrayList<>();
        int i = startIndex + 1;
        while (i < lines.size()) {
            String originalLine = lines.get(i).trim(); String line = originalLine;
            for (Map.Entry<String, String> var : variables.entrySet()) line = line.replace(var.getKey(), var.getValue());
            if (line.isEmpty() || line.startsWith("TRIGGER:") || line.startsWith("PestCD:") || line.matches("PestAlive\\d+:.*") || line.startsWith("#") || line.startsWith("@")) break;
            if (line.startsWith("DELAY:")) { commandDelay = parseTime(line.substring("DELAY:".length()).trim()); }
            else if (line.startsWith("BLOCK:")) {
                String[] parts = line.substring("BLOCK:".length()).trim().split("\\s+for\\s+", 2);
                if (parts.length == 2) { String cmd = parts[0].trim(); if (cmd.startsWith("/")) cmd = cmd.substring(1); blockedCommands.add(new ChatTrigger.BlockedCommand(cmd, (int)(parseTime(parts[1].trim()) / 1000))); }
            } else {
                Object[] out = new Object[]{command, commandDelay, blockInputs, blockInputsDelay};
                if (parseActionLine(line, originalLine, actionList, keybindActions, out)) { command = (String) out[0]; commandDelay = (Long) out[1]; blockInputs = (Boolean) out[2]; blockInputsDelay = (Long) out[3]; }
            }
            i++;
        }
        if (!command.isEmpty() || !keybindActions.isEmpty()) pestAliveTriggers.add(new PestAliveTrigger(threshold, command, commandDelay, keybindActions, blockInputs, blockInputsDelay, blockedCommands, actionList));
        return i;
    }

    private static float[] parseRangeWithSpaces(String s) {
        s = s.trim().replaceAll("\\s*-\\s*", "-");
        String[] parts = s.split("(?<=\\d)-", 2);
        if (parts.length != 2) throw new IllegalArgumentException("Cannot parse range: " + s);
        return new float[]{ Float.parseFloat(parts[0]), Float.parseFloat(parts[1]) };
    }

    private static int parseCooldownSeconds(String text) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)m\\s*(\\d+)s").matcher(text);
            if (m.find()) return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
            m = java.util.regex.Pattern.compile("(\\d+):(\\d{2})").matcher(text);
            if (m.find()) return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
        } catch (Exception ignored) {}
        return -1;
    }

    private static long parseTime(String timeStr) {
        timeStr = timeStr.trim().toLowerCase();
        if (timeStr.endsWith("ms")) return Long.parseLong(timeStr.substring(0, timeStr.length() - 2).trim());
        if (timeStr.endsWith("s")) return Long.parseLong(timeStr.substring(0, timeStr.length() - 1).trim()) * 1000;
        return Long.parseLong(timeStr);
    }

    private static int parseOldFormatTrigger(List<String> lines, int index, String line) {
        try {
            boolean blockInputs = false;
            if (line.toLowerCase().startsWith("blockinputs:")) { blockInputs = true; line = line.substring("blockinputs:".length()).trim(); }
            List<ChatTrigger.BlockedCommand> blockedCommands = new ArrayList<>();
            if (line.toLowerCase().startsWith("blockcommand ")) {
                String[] blockParts = line.substring("blockcommand ".length()).split(":", 2);
                if (blockParts.length == 2) {
                    String[] dc = blockParts[0].trim().split("\\s+", 2);
                    if (dc.length == 2) {
                        try {
                            int dur = Integer.parseInt(dc[0].trim());
                            String cmd = dc[1].trim(); if (cmd.startsWith("/")) cmd = cmd.substring(1);
                            blockedCommands.add(new ChatTrigger.BlockedCommand(cmd, dur));
                            line = blockParts[1].trim();
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            String[] parts = null;
            if (line.contains("=")) parts = line.split("=", 999);
            else if (line.contains("->")) parts = line.split("->", 4);
            if (parts == null || parts.length < 2) return index + 1;
            String triggerText = parts[0].trim().replace("\"", "");
            if (triggerText == null || triggerText.trim().isEmpty()) return index + 1;
            if (triggerText.endsWith("+")) {
                triggerText = triggerText.substring(0, triggerText.length() - 1).trim();
                List<ChatTrigger.KeybindAction> keybindActions = new ArrayList<>();
                for (int j = 1; j < parts.length; j++) {
                    String part = parts[j].trim(); if (part.isEmpty()) continue;
                    if (part.endsWith("+")) part = part.substring(0, part.length() - 1).trim();
                    String[] delayKey = part.split("\\s+", 2);
                    if (delayKey.length == 2) { try { keybindActions.add(new ChatTrigger.KeybindAction(Long.parseLong(delayKey[0].trim()), delayKey[1].trim())); } catch (NumberFormatException ignored) {} }
                }
                if (!keybindActions.isEmpty()) { try { triggers.add(new ChatTrigger(triggerText, "", 0, keybindActions, blockInputs, 0, blockedCommands)); } catch (IllegalArgumentException ignored) {} }
                return index + 1;
            }
            String command = ""; long delayMs = 0; String keybind = null; long keybindDelayMs = 100;
            if (parts.length == 2) {
                String cmdPart = parts[1].trim().replace("\"", "");
                if (cmdPart.contains("+=")) {
                    String[] allParts = cmdPart.split("\\+=");
                    command = allParts[0].trim();
                    List<ChatTrigger.KeybindAction> keybindActions = new ArrayList<>();
                    for (int j = 1; j < allParts.length; j++) {
                        String[] delayKey = allParts[j].trim().split("\\s+", 2);
                        if (delayKey.length == 2) { try { keybindActions.add(new ChatTrigger.KeybindAction(Long.parseLong(delayKey[0].trim()), delayKey[1].trim())); } catch (NumberFormatException ignored) {} }
                    }
                    if (!keybindActions.isEmpty()) { try { triggers.add(new ChatTrigger(triggerText, command, delayMs, keybindActions, blockInputs, 0, blockedCommands)); } catch (IllegalArgumentException ignored) {} return index + 1; }
                } else if (cmdPart.contains("+")) {
                    String[] ck = cmdPart.split("\\+", 2); command = ck[0].trim(); keybind = ck[1].trim();
                } else {
                    if (cmdPart.length() <= 10 && !cmdPart.startsWith("/") && !cmdPart.startsWith(".") && getKeyCode(cmdPart.toLowerCase()) != -1) { keybind = cmdPart; keybindDelayMs = 0; }
                    else command = cmdPart;
                }
            } else if (parts.length == 3) {
                String part1 = parts[1].trim();
                if (part1.endsWith("+")) {
                    command = part1.substring(0, part1.length() - 1).trim().replace("\"", "");
                    String[] delayKey = parts[2].trim().split("\\s+", 2);
                    if (delayKey.length == 2) { try { keybindDelayMs = Long.parseLong(delayKey[0].trim()); keybind = delayKey[1].trim(); } catch (NumberFormatException ignored) {} }
                } else {
                    try {
                        delayMs = Long.parseLong(part1);
                        String cmdPart = parts[2].trim().replace("\"", "");
                        if (cmdPart.contains("+=")) {
                            String[] ckp = cmdPart.split("\\+=", 2); command = ckp[0].trim();
                            String[] dk = ckp[1].trim().split("\\s+", 2);
                            if (dk.length == 2) { try { keybindDelayMs = Long.parseLong(dk[0].trim()); keybind = dk[1].trim(); } catch (NumberFormatException ignored) {} }
                        } else if (cmdPart.contains("+")) { String[] ck = cmdPart.split("\\+", 2); command = ck[0].trim(); keybind = ck[1].trim(); }
                        else command = cmdPart;
                    } catch (NumberFormatException e) { command = part1.replace("\"", ""); }
                }
            } else if (parts.length == 4) {
                try { delayMs = Long.parseLong(parts[1].trim()); command = parts[2].trim().replace("\"", ""); keybind = parts[3].trim().replace("\"", ""); } catch (NumberFormatException e) { return index + 1; }
            }
            try { triggers.add(new ChatTrigger(triggerText, command, delayMs, keybind, keybindDelayMs, blockInputs, 0, blockedCommands)); } catch (IllegalArgumentException ignored) {}
            return index + 1;
        } catch (Exception e) { LOGGER.error("Failed to parse old format trigger on line {}", index + 1, e); return index + 1; }
    }

    private static void createDefaultConfig() throws IOException {
        Files.write(configPath, List.of(
            "# Taun+++ Triggers Configuration",
            "# Run /pest setup for automatic configuration",
            ""
        ));
    }

    private static class GuiOpenTimeoutException extends Exception { GuiOpenTimeoutException() { super("WAITONGUIOPEN timed out"); } }

    private static void waitForGuiOpenThenClose(long preOpenDelayMs) throws InterruptedException, GuiOpenTimeoutException {
        if (preOpenDelayMs > 0) Thread.sleep(preOpenDelayMs);
        MinecraftClient client = MinecraftClient.getInstance();
        long start = System.currentTimeMillis();
        while (client.currentScreen == null) {
            if (System.currentTimeMillis() - start > 10000) throw new GuiOpenTimeoutException();
            Thread.sleep(20);
        }
        waitForGuiClosure(0);
    }

    private static void waitForGuiClosure(long initialDelayMs) throws InterruptedException {
        if (initialDelayMs > 0) Thread.sleep(initialDelayMs);
        MinecraftClient client = MinecraftClient.getInstance();
        long start = System.currentTimeMillis();
        while (client.currentScreen != null) {
            if (System.currentTimeMillis() - start > 10000) break;
            Thread.sleep(20);
        }
    }

    private static void executeActionSequence(List<ChatTrigger.TriggerAction> actions, boolean blockInputs, long blockInputsDelay) throws InterruptedException {
        if (actions.isEmpty()) return;
        if (blockInputs && blockInputsDelay > 0) Thread.sleep(blockInputsDelay);
        if (blockInputs) blockingInputs = true;
        try {
            boolean skipToJacobTrue = false;
            boolean skipGuiDueToCropFever = false;
            for (ChatTrigger.TriggerAction action : actions) {
                if (skipToJacobTrue && action.type != ChatTrigger.TriggerAction.Type.IFJACOB_TRUE) continue;
                if (action.delayMs > 0) Thread.sleep(applyRandomDelay(action.delayMs));
                switch (action.type) {
                    case COMMAND -> executeCommand(action.value);
                    case PRESS -> pressKey(action.value, action.originalKey);
                    case HOLD -> holdKey(action.value, action.originalKey);
                    case UNHOLD -> unholdKey(action.value, action.originalKey);
                    case ROTATE_TO -> {
                        float y = action.yawMin + (float)(Math.random() * (action.yawMax - action.yawMin));
                        float p = action.pitchMin + (float)(Math.random() * (action.pitchMax - action.pitchMin));
                        rotateTo(y, p, action.rotateDurationMs);
                    }
                    case ETHERWARP_TO -> {
                        float y = action.yawMin + (float)(Math.random() * (action.yawMax - action.yawMin));
                        float p = action.pitchMin + (float)(Math.random() * (action.pitchMax - action.pitchMin));
                        float lp = action.landPitchMin + (float)(Math.random() * (action.landPitchMax - action.landPitchMin));
                        performEtherwarp(y, p, lp);
                    }
                    case EQSWAP -> {
                        executeSingleCommand("stats");
                        Thread.sleep(200);
                        performEqSwap(action.value);
                    }
                    case RODSWAP -> performRodSwap();
                    case IFJACOB_FALSE -> {
                        if (jacobContestActive) {
                            skipToJacobTrue = true; // skip to IFJACOB_TRUE block
                        }
                    }
                    case IFJACOB_TRUE -> {
                        if (!jacobContestActive) {
                            return; // IFJACOB_FALSE block already ran, done
                        }
                        skipToJacobTrue = false; // Jacob IS active, continue
                    }
                    case WAITFORCHAT -> {
                        waitForChatPhrase = action.value.toLowerCase();
                        waitForChatMatched = false;
                        long waitStart = System.currentTimeMillis();
                        while (!waitForChatMatched && System.currentTimeMillis() - waitStart < 10 * 60 * 1000) {
                            Thread.sleep(200);
                        }
                        waitForChatPhrase = null;
                    }
                    case WAITFORJACOBTIMER -> {
                        int threshold = 5;
                        try { threshold = Integer.parseInt(action.value); } catch (NumberFormatException ignored) {}
                        while (jacobContestActive && jacobTimerSeconds > threshold) {
                            Thread.sleep(500);
                        }
                    }
                    case IFCROPFEVER_SKIP_GUI -> {
                        if (cropFeverActive) skipGuiDueToCropFever = true;
                    }
                    case RETRYUNTILSKYBLOCK -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        boolean inSkyblock = false;
                        for (int attempt = 1; attempt <= 10 && !inSkyblock; attempt++) {
                            // Send /skyblock
                            mc.execute(() -> { if (mc.player != null) mc.player.networkHandler.sendChatCommand("skyblock"); });
                            LOGGER.info("[ShutdownSafety] /skyblock attempt {}/10", attempt);
                            // Wait up to 20s for world change (player goes null then comes back)
                            long deadline = System.currentTimeMillis() + 20_000;
                            // Wait for player to go null (leaving lobby)
                            while (mc.player != null && System.currentTimeMillis() < deadline) Thread.sleep(300);
                            if (mc.player == null) {
                                // Wait for player to come back (joined SkyBlock)
                                while (mc.player == null && System.currentTimeMillis() < deadline) Thread.sleep(300);
                                if (mc.player != null) { inSkyblock = true; break; }
                            }
                            // If no world change detected, wait out the rest of the 20s then retry
                            long remaining = deadline - System.currentTimeMillis();
                            if (remaining > 0) Thread.sleep(remaining);
                        }
                        if (!inSkyblock) LOGGER.warn("[ShutdownSafety] Failed to join SkyBlock after 10 attempts");
                    }
                    case WAITONGUIOPEN -> {
                        if (!skipGuiDueToCropFever) {
                            try { waitForGuiOpenThenClose(action.delayMs); } catch (GuiOpenTimeoutException e) { return; }
                        }
                    }
                }
            }
        } finally { blockingInputs = false; }
    }

    private static void performEtherwarp(float warpYaw, float warpPitch, float landPitch) throws InterruptedException {
        MinecraftClient client = MinecraftClient.getInstance();
        int aotvSlot = findAotvSlotInHotbar();
        if (aotvSlot == -1) return;
        setSelectedSlot(aotvSlot - 1);
        Thread.sleep(100);
        holdKey("shift", "shift");
        Thread.sleep(50);
        rotateTo(warpYaw, warpPitch, rotateSpeedMs);
        Thread.sleep(50);
        pressKey("rmb", "rmb");
        Thread.sleep(150);
        unholdKey("shift", "shift");
        Thread.sleep(50);
        rotateTo(warpYaw, landPitch, rotateSpeedMs);
    }

    private static void performEqSwap(String target) throws InterruptedException {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        String swapLabel = target.equals("PEST") ? "Pest Hunter armor" : "Blossom/Lotus armor";
        java.util.List<String> keywords = new ArrayList<>();
        if (target.equals("PEST")) { keywords.add("pesthunter"); keywords.add("pest vest"); }
        else if (target.equals("BLOSSOM_LOTUS")) {
            keywords.add("blossom"); keywords.add("lotus"); // match all blossom/lotus pieces
        }
        int waited = 0;
        while (client.currentScreen == null && waited < 2000) { Thread.sleep(50); waited += 50; }
        if (client.currentScreen == null) return;
        java.util.List<Integer> matchingSlots = new ArrayList<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        client.execute(() -> {
            try {
                if (client.player.currentScreenHandler != null) {
                    var slots = client.player.currentScreenHandler.slots;
                    for (int idx = 0; idx < slots.size(); idx++) {
                        var slot = slots.get(idx);
                        if (idx < 45 || !slot.hasStack()) continue;
                        String n = slot.getStack().getName().getString().toLowerCase();
                        for (String kw : keywords) { if (n.contains(kw)) { matchingSlots.add(idx); break; } }
                    }
                }
            } finally { latch.countDown(); }
        });
        latch.await();
        // For BLOSSOM_LOTUS: exclude cloaks from general match, then add the correct cloak
        if (target.equals("BLOSSOM_LOTUS")) {
            java.util.List<Integer> cloakSlot = new ArrayList<>();
            java.util.List<Integer> nonCloakSlots = new ArrayList<>();
            java.util.concurrent.CountDownLatch cloakLatch = new java.util.concurrent.CountDownLatch(1);
            client.execute(() -> {
                try {
                    if (client.player.currentScreenHandler != null) {
                        var slots = client.player.currentScreenHandler.slots;
                        for (int idx : matchingSlots) {
                            String n = slots.get(idx).getStack().getName().getString().toLowerCase();
                            if (!n.contains("cloak")) nonCloakSlots.add(idx);
                        }
                        // Live-check tablist for Jacob's Contest to avoid stale flag
                        boolean jacobActive = false;
                        if (zorroEnabled && client.player.networkHandler != null) {
                            java.util.List<net.minecraft.client.network.PlayerListEntry> jEntries = new java.util.ArrayList<>(client.player.networkHandler.getPlayerList());
                            for (int ji = 0; ji < jEntries.size(); ji++) {
                                if (jEntries.get(ji).getDisplayName() == null) continue;
                                String jt = jEntries.get(ji).getDisplayName().getString().replaceAll("§.", "");
                                if (jt.contains("Jacob's Contest:")) {
                                    jacobActive = jt.matches(".*\\d+m.*left.*") || jt.matches(".*\\d+s.*left.*");
                                    if (debugEnabled) client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7[Zorro] Jacob entry: '" + jt + "' active=" + jacobActive), false);
                                    break;
                                }
                            }
                        }
                        if (debugEnabled) client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7[Zorro] jacobActive=" + jacobActive + " zorroEnabled=" + zorroEnabled), false);
                        // Pick correct cloak: zorro during jacob (fallback to lotus cloak), lotus cloak otherwise
                        String cloakKeyword = (jacobActive && zorroEnabled) ? "zorro" : "lotus cloak";
                        for (int idx = 0; idx < slots.size(); idx++) {
                            var slot = slots.get(idx);
                            if (!slot.hasStack()) continue;
                            String n = slot.getStack().getName().getString().toLowerCase();
                            if (n.contains(cloakKeyword)) { cloakSlot.add(idx); break; }
                        }
                        // Fallback: if zorro not found during jacob, try lotus cloak
                        if (cloakSlot.isEmpty() && jacobActive && zorroEnabled) {
                            for (int idx = 0; idx < slots.size(); idx++) {
                                var slot = slots.get(idx);
                                if (!slot.hasStack()) continue;
                                String n = slot.getStack().getName().getString().toLowerCase();
                                if (n.contains("lotus cloak") || n.contains("blossom cloak")) { cloakSlot.add(idx); break; }
                            }
                        }
                    }
                } finally { cloakLatch.countDown(); }
            });
            cloakLatch.await();
            matchingSlots.clear();
            matchingSlots.addAll(nonCloakSlots);
            matchingSlots.addAll(cloakSlot);
        }
        for (int slotIdx : matchingSlots) {
            final int fs = slotIdx;
            client.execute(() -> {
                if (client.player.currentScreenHandler != null)
                    client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, fs, 0, net.minecraft.screen.slot.SlotActionType.PICKUP, client.player);
            });
            Thread.sleep(325);
        }
        // Close the screen and wait for it to actually close before continuing
        if (client.currentScreen != null) {
            client.execute(() -> { if (client.currentScreen != null) client.player.closeHandledScreen(); });
            int closeWait = 0;
            while (client.currentScreen != null && closeWait < 2000) { Thread.sleep(50); closeWait += 50; }
        }
    }

    private static void executeCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (command.contains(";")) {
            String[] commands = command.split(";");
            for (int i = 0; i < commands.length; i++) {
                final String cmd = commands[i].trim(); final int d = i * 100;
                new Thread(() -> { try { Thread.sleep(d); executeSingleCommand(cmd); } catch (InterruptedException e) { LOGGER.error("Command interrupted", e); } }).start();
            }
            return;
        }
        executeSingleCommand(command);
    }

    private static void executeSingleCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (command.equalsIgnoreCase("unblockcommands")) { blockedCommands.clear(); return; }
        String check = command.startsWith("/") ? command.substring(1) : command;
        for (Map.Entry<String, Long> e : blockedCommands.entrySet()) {
            if ((check.equals(e.getKey()) || check.startsWith(e.getKey() + " ")) && e.getValue() != null && System.currentTimeMillis() < e.getValue()) return;
        }
        if (command.startsWith("/")) command = command.substring(1);
        final String fc = command;
        if (command.startsWith(".")) client.execute(() -> { try { client.player.networkHandler.sendChatMessage(fc); } catch (Exception e) { LOGGER.error("Failed to send chat: {}", fc, e); } });
        else client.execute(() -> { try { client.player.networkHandler.sendChatCommand(fc); } catch (Exception e) { LOGGER.error("Failed to execute command: {}", fc, e); } });
    }

    private static void activateCommandBlock(String command, int durationSeconds) {
        if (command.startsWith("/")) command = command.substring(1);
        blockedCommands.put(command, System.currentTimeMillis() + (durationSeconds * 1000L));
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) client.player.sendMessage(Text.literal("§c§lTaun+++ >> §c/" + command + " §7blocked for §e" + durationSeconds + "s"), false);
    }

    public static boolean isCommandBlocked(String command) {
        String check = command.startsWith("/") ? command.substring(1) : command;
        for (Map.Entry<String, Long> e : blockedCommands.entrySet()) {
            if ((check.equals(e.getKey()) || check.startsWith(e.getKey() + " ")) && e.getValue() != null && System.currentTimeMillis() < e.getValue()) return true;
        }
        return false;
    }

    public static long getCommandBlockTimeRemaining(String command) {
        String check = command.startsWith("/") ? command.substring(1) : command;
        for (Map.Entry<String, Long> e : blockedCommands.entrySet()) {
            if ((check.equals(e.getKey()) || check.startsWith(e.getKey() + " ")) && e.getValue() != null && System.currentTimeMillis() < e.getValue())
                return (e.getValue() - System.currentTimeMillis()) / 1000;
        }
        return 0;
    }

    private static long applyRandomDelay(long baseDelay) {
        if (randomDelayRange <= 0) return baseDelay;
        long delta = (long)(random.nextInt(randomDelayRange * 2 + 1)) - randomDelayRange;
        return Math.max(0, baseDelay + delta);
    }

    public static void showRandomDelay() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Random delay: " + (randomDelayRange == 0 ? "§cDISABLED" : "§a±" + randomDelayRange + "ms")), false);
    }

    public static void setRandomDelay(int maxRandomMs) {
        randomDelayRange = Math.max(0, maxRandomMs);
        saveSettings();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Random delay: " + (randomDelayRange == 0 ? "§cDISABLED" : "§a±" + randomDelayRange + "ms")), false);
    }

    public static int getRandomDelay() { return randomDelayRange; }

    private static void pressKey(String key, String originalKey) {
        String resolved = key;
        if ("@ROD_SLOT".equals(originalKey)) { int s = findRodSlotInHotbar(); if (s != -1) resolved = String.valueOf(s); }
        else if ("@AOTV_SLOT".equals(originalKey)) { int s = findAotvSlotInHotbar(); if (s != -1) resolved = String.valueOf(s); }
        else if ("@FARMING_TOOL_SLOT".equals(originalKey)) { int s = findFarmingToolSlotInHotbar(); if (s != -1) resolved = String.valueOf(s); }
        else if ("@ABIPHONE_SLOT".equals(originalKey)) { if (abiphoneSlot > 0) resolved = String.valueOf(abiphoneSlot); }
        pressKey(resolved);
    }

    private static void pressKey(String key) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        String keyLower = key.toLowerCase().trim();
        // First try to resolve via Minecraft's actual keybind settings
        int keyCode = resolveMinecraftKeybind(keyLower);
        if (keyCode == -999) keyCode = getKeyCode(keyLower); // fall through to static map
        if (keyCode == -1) return;
        if (keyCode < 0) { pressMouse(keyCode, key); return; }
        final int fKeyCode = keyCode;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        client.execute(() -> {
            try {
                long window = client.getWindow().getHandle();
                java.lang.reflect.Method m = findOnKeyMethod(client);
                if (m == null) return;
                m.setAccessible(true);
                m.invoke(client.keyboard, window, fKeyCode, 0, GLFW.GLFW_PRESS, 0);
                final java.lang.reflect.Method fm = m;
                new Thread(() -> { try { Thread.sleep(50); client.execute(() -> { try { fm.invoke(client.keyboard, window, fKeyCode, 0, GLFW.GLFW_RELEASE, 0); } catch (Exception e) { LOGGER.error("Release key failed", e); } }); } catch (InterruptedException e) {} }).start();
            } catch (Exception e) { LOGGER.error("Failed to press key: '{}'", key, e); }
            finally { latch.countDown(); }
        });
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void pressMouse(int mouseCode, String key) {
        MinecraftClient client = MinecraftClient.getInstance();
        // RMB: use interactItem directly — reliable across all contexts
        if (mouseCode == -101) {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            client.execute(() -> {
                try {
                    if (client.player != null && client.interactionManager != null)
                        client.interactionManager.interactItem(client.player, net.minecraft.util.Hand.MAIN_HAND);
                } finally { latch.countDown(); }
            });
            try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return;
        }
        int glfwButton = switch (mouseCode) { case -100 -> GLFW.GLFW_MOUSE_BUTTON_LEFT; case -102 -> GLFW.GLFW_MOUSE_BUTTON_MIDDLE; default -> -1; };
        if (glfwButton == -1) return;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        client.execute(() -> {
            try {
                long window = client.getWindow().getHandle();
                java.lang.reflect.Method m = null;
                try { m = client.mouse.getClass().getDeclaredMethod("onMouseButton", long.class, int.class, int.class, int.class); }
                catch (NoSuchMethodException e) { for (java.lang.reflect.Method mm : client.mouse.getClass().getDeclaredMethods()) { if (mm.getParameterTypes().length == 4 && mm.getParameterTypes()[0] == long.class) { m = mm; break; } } }
                if (m == null) return;
                m.setAccessible(true);
                m.invoke(client.mouse, window, glfwButton, GLFW.GLFW_PRESS, 0);
                final java.lang.reflect.Method fm = m;
                new Thread(() -> { try { Thread.sleep(50); client.execute(() -> { try { fm.invoke(client.mouse, window, glfwButton, GLFW.GLFW_RELEASE, 0); } catch (Exception e) {} }); } catch (InterruptedException e) {} }).start();
            } catch (Exception e) { LOGGER.error("Failed to press mouse: '{}'", key, e); }
            finally { latch.countDown(); }
        });
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.lang.reflect.Method> heldKeys = new java.util.concurrent.ConcurrentHashMap<>();

    private static void setSelectedSlot(int slot) { pressKey(String.valueOf(slot + 1), String.valueOf(slot + 1)); }

    private static void holdKey(String key, String originalKey) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        String keyLower = key.toLowerCase().trim();
        int keyCode = resolveMinecraftKeybind(keyLower);
        if (keyCode == -999) keyCode = getKeyCode(keyLower);
        if (keyCode == -1) return;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        if (keyCode < 0) {
            // RMB: interactItem is reliable; treat hold same as press since Minecraft handles it per-tick
            if (keyCode == -101) {
                client.execute(() -> {
                    try {
                        if (client.player != null && client.interactionManager != null)
                            client.interactionManager.interactItem(client.player, net.minecraft.util.Hand.MAIN_HAND);
                    } finally { latch.countDown(); }
                });
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return;
            }
            final int glfwButton = switch (keyCode) {
                case -100 -> GLFW.GLFW_MOUSE_BUTTON_LEFT;
                case -102 -> GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
                default -> -1;
            };
            if (glfwButton == -1) return;
            final int fCode = keyCode;
            client.execute(() -> {
                try {
                    long w = client.getWindow().getHandle();
                    java.lang.reflect.Method m = null;
                    try { m = client.mouse.getClass().getDeclaredMethod("onMouseButton", long.class, int.class, int.class, int.class); }
                    catch (NoSuchMethodException e) { for (java.lang.reflect.Method mm : client.mouse.getClass().getDeclaredMethods()) { if (mm.getParameterTypes().length == 4 && mm.getParameterTypes()[0] == long.class) { m = mm; break; } } }
                    if (m == null) return;
                    m.setAccessible(true);
                    m.invoke(client.mouse, w, glfwButton, GLFW.GLFW_PRESS, 0);
                    heldKeys.put(fCode, m);
                } catch (Exception e) { LOGGER.error("[HOLD/mouse] Failed: '{}'", key, e); }
                finally { latch.countDown(); }
            });
        } else {
            final int fKeyCode = keyCode;
            client.execute(() -> {
                try { long w = client.getWindow().getHandle(); java.lang.reflect.Method m = findOnKeyMethod(client); if (m == null) return; m.setAccessible(true); m.invoke(client.keyboard, w, fKeyCode, 0, GLFW.GLFW_PRESS, 0); heldKeys.put(fKeyCode, m); }
                catch (Exception e) { LOGGER.error("[HOLD] Failed: '{}'", key, e); }
                finally { latch.countDown(); }
            });
        }
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void unholdKey(String key, String originalKey) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        String keyLower = key.toLowerCase().trim();
        int keyCode = resolveMinecraftKeybind(keyLower);
        if (keyCode == -999) keyCode = getKeyCode(keyLower);
        if (keyCode == -1) return;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        if (keyCode < 0) {
            // RMB unhold is a no-op — interactItem in holdKey already fired as a single call
            if (keyCode == -101) return;
            final int glfwButton = switch (keyCode) {
                case -100 -> GLFW.GLFW_MOUSE_BUTTON_LEFT;
                case -102 -> GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
                default -> -1;
            };
            if (glfwButton == -1) return;
            final int fCode = keyCode;
            client.execute(() -> {
                try {
                    long w = client.getWindow().getHandle();
                    java.lang.reflect.Method m = heldKeys.remove(fCode);
                    if (m == null) {
                        try { m = client.mouse.getClass().getDeclaredMethod("onMouseButton", long.class, int.class, int.class, int.class); }
                        catch (NoSuchMethodException e) { for (java.lang.reflect.Method mm : client.mouse.getClass().getDeclaredMethods()) { if (mm.getParameterTypes().length == 4 && mm.getParameterTypes()[0] == long.class) { m = mm; break; } } }
                    }
                    if (m == null) return;
                    m.setAccessible(true);
                    m.invoke(client.mouse, w, glfwButton, GLFW.GLFW_RELEASE, 0);
                } catch (Exception e) { LOGGER.error("[UNHOLD/mouse] Failed: '{}'", key, e); }
                finally { latch.countDown(); }
            });
        } else {
            final int fKeyCode = keyCode;
            client.execute(() -> {
                try { long w = client.getWindow().getHandle(); java.lang.reflect.Method m = heldKeys.remove(fKeyCode); if (m == null) m = findOnKeyMethod(client); if (m == null) return; m.setAccessible(true); m.invoke(client.keyboard, w, fKeyCode, 0, GLFW.GLFW_RELEASE, 0); }
                catch (Exception e) { LOGGER.error("[UNHOLD] Failed: '{}'", key, e); }
                finally { latch.countDown(); }
            });
        }
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static java.lang.reflect.Method findOnKeyMethod(MinecraftClient client) {
        try { return client.keyboard.getClass().getDeclaredMethod("onKey", long.class, int.class, int.class, int.class, int.class); }
        catch (NoSuchMethodException e1) {
            try { return client.keyboard.getClass().getDeclaredMethod("method_22676", long.class, int.class, int.class, int.class, int.class); }
            catch (NoSuchMethodException e2) {
                for (java.lang.reflect.Method m : client.keyboard.getClass().getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 5 && p[0] == long.class && p[1] == int.class && p[2] == int.class && p[3] == int.class && p[4] == int.class) return m;
                }
            }
        }
        return null;
    }

    private static void rotateTo(float targetYaw, float targetPitch, long durationMs) throws InterruptedException {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        float[] start = new float[2];
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        client.execute(() -> { if (client.player != null) { start[0] = client.player.getYaw(); start[1] = client.player.getPitch(); } latch.countDown(); });
        latch.await();
        float yawDelta = targetYaw - start[0];
        while (yawDelta > 180) yawDelta -= 360;
        while (yawDelta < -180) yawDelta += 360;
        float pitchDelta = targetPitch - start[1];
        long stepMs = 10; long steps = Math.max(1, durationMs / stepMs);
        java.util.Random rng = new java.util.Random();
        for (long step = 1; step <= steps; step++) {
            float t = (float) step / steps;
            float e = t < 0.5f ? 2 * t * t : 1 - (-2 * t + 2) * (-2 * t + 2) / 2;
            float ny = start[0] + yawDelta * e + (step < steps ? (float)(rng.nextGaussian() * 0.15) : 0);
            float np = start[1] + pitchDelta * e + (step < steps ? (float)(rng.nextGaussian() * 0.15) : 0);
            client.execute(() -> { if (client.player != null) { client.player.setYaw(ny); client.player.setPitch(np); } });
            Thread.sleep(stepMs);
        }
        client.execute(() -> { if (client.player != null) { client.player.setYaw(targetYaw); client.player.setPitch(targetPitch); } });
    }

    /**
     * Resolves a semantic Minecraft action name to its actual bound GLFW key code.
     * e.g. "sneak" -> whatever the player has bound to sneak (shift by default, but could be ctrl etc.)
     * Returns -999 if not a known action name, so caller can fall through to getKeyCode().
     * Returns -1 if action is known but unbound.
     */
    private static int resolveMinecraftKeybind(String key) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return -999;
        net.minecraft.client.option.GameOptions opts = mc.options;
        if (opts == null) return -999;
        return switch (key) {
            case "sneak", "shift"         -> getBoundKey(opts.sneakKey);
            case "use", "rmb", "rightclick", "mouse2" -> getBoundKey(opts.useKey);
            case "attack", "lmb", "leftclick", "mouse1" -> getBoundKey(opts.attackKey);
            case "jump", "space"          -> getBoundKey(opts.jumpKey);
            case "sprint"                 -> getBoundKey(opts.sprintKey);
            case "forward"                -> getBoundKey(opts.forwardKey);
            case "back"                   -> getBoundKey(opts.backKey);
            case "left"                   -> getBoundKey(opts.leftKey);
            case "right"                  -> getBoundKey(opts.rightKey);
            case "inventory"              -> getBoundKey(opts.inventoryKey);
            case "drop"                   -> getBoundKey(opts.dropKey);
            case "swap"                   -> getBoundKey(opts.swapHandsKey);
            default -> -999; // not a recognized action name
        };
    }

    /** Extracts the GLFW key code from a KeyBinding using the public translation key string.
     *  Format is "key.keyboard.X" for keyboard keys or "key.mouse.N" for mouse buttons. */
    private static int getBoundKey(net.minecraft.client.option.KeyBinding binding) {
        if (binding == null) return -1;
        try {
            net.minecraft.client.util.InputUtil.Key key =
                net.minecraft.client.util.InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey());
            if (key == null || key.equals(net.minecraft.client.util.InputUtil.UNKNOWN_KEY)) return -1;
            net.minecraft.client.util.InputUtil.Type type = key.getCategory();
            if (type == net.minecraft.client.util.InputUtil.Type.KEYSYM) {
                return key.getCode();
            } else if (type == net.minecraft.client.util.InputUtil.Type.MOUSE) {
                return switch (key.getCode()) {
                    case GLFW.GLFW_MOUSE_BUTTON_LEFT   -> -100;
                    case GLFW.GLFW_MOUSE_BUTTON_RIGHT  -> -101;
                    case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> -102;
                    default -> -1;
                };
            }
        } catch (Exception e) {
            LOGGER.warn("[KeyBind] Could not resolve keybind: {}", e.getMessage());
        }
        return -1;
    }

    private static int getKeyCode(String key) {
        return switch (key) {
            case "1" -> GLFW.GLFW_KEY_1; case "2" -> GLFW.GLFW_KEY_2; case "3" -> GLFW.GLFW_KEY_3;
            case "4" -> GLFW.GLFW_KEY_4; case "5" -> GLFW.GLFW_KEY_5; case "6" -> GLFW.GLFW_KEY_6;
            case "7" -> GLFW.GLFW_KEY_7; case "8" -> GLFW.GLFW_KEY_8; case "9" -> GLFW.GLFW_KEY_9;
            case "0" -> GLFW.GLFW_KEY_0;
            case "a" -> GLFW.GLFW_KEY_A; case "b" -> GLFW.GLFW_KEY_B; case "c" -> GLFW.GLFW_KEY_C;
            case "d" -> GLFW.GLFW_KEY_D; case "e" -> GLFW.GLFW_KEY_E; case "f" -> GLFW.GLFW_KEY_F;
            case "g" -> GLFW.GLFW_KEY_G; case "h" -> GLFW.GLFW_KEY_H; case "i" -> GLFW.GLFW_KEY_I;
            case "j" -> GLFW.GLFW_KEY_J; case "k" -> GLFW.GLFW_KEY_K; case "l" -> GLFW.GLFW_KEY_L;
            case "m" -> GLFW.GLFW_KEY_M; case "n" -> GLFW.GLFW_KEY_N; case "o" -> GLFW.GLFW_KEY_O;
            case "p" -> GLFW.GLFW_KEY_P; case "q" -> GLFW.GLFW_KEY_Q; case "r" -> GLFW.GLFW_KEY_R;
            case "s" -> GLFW.GLFW_KEY_S; case "t" -> GLFW.GLFW_KEY_T; case "u" -> GLFW.GLFW_KEY_U;
            case "v" -> GLFW.GLFW_KEY_V; case "w" -> GLFW.GLFW_KEY_W; case "x" -> GLFW.GLFW_KEY_X;
            case "y" -> GLFW.GLFW_KEY_Y; case "z" -> GLFW.GLFW_KEY_Z;
            case "enter", "return" -> GLFW.GLFW_KEY_ENTER;
            case "space" -> GLFW.GLFW_KEY_SPACE;
            case "esc", "escape" -> GLFW.GLFW_KEY_ESCAPE;
            case "tab" -> GLFW.GLFW_KEY_TAB;
            case "shift" -> GLFW.GLFW_KEY_LEFT_SHIFT;
            case "ctrl", "control" -> GLFW.GLFW_KEY_LEFT_CONTROL;
            case "alt" -> GLFW.GLFW_KEY_LEFT_ALT;
            case "leftclick", "lmb", "mouse1" -> -100;
            case "rightclick", "rmb", "mouse2" -> -101;
            case "middleclick", "mmb", "mouse3" -> -102;
            default -> -1;
        };
    }

    public static void reloadConfig() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) client.player.sendMessage(Text.literal("§c§lTaun+++ >> §aReloading triggers."), false);
        loadTriggers();
        loadCoordinateTriggers();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §aLoaded " + triggers.size() + " chat triggers"), false);
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §aLoaded " + coordinateTriggers.size() + " coordinate triggers"), false);
        }
    }

    public static void toggleChatTriggers() {
        chatTriggersEnabled = !chatTriggersEnabled;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Chat Triggers: " + (chatTriggersEnabled ? "§aENABLED" : "§cDISABLED")), false);
        saveSettings();
    }

    public static void toggleCoordTriggers() {
        coordTriggersEnabled = !coordTriggersEnabled;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Coordinate Triggers: " + (coordTriggersEnabled ? "§aENABLED" : "§cDISABLED")), false);
        saveSettings();
    }

    public static void toggleAllTriggers() {
        if (chatTriggersEnabled || coordTriggersEnabled) { chatTriggersEnabled = false; coordTriggersEnabled = false; }
        else { chatTriggersEnabled = true; coordTriggersEnabled = true; }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7All Triggers: " + (chatTriggersEnabled ? "§aENABLED" : "§cDISABLED")), false);
        saveSettings();
    }

    public static void toggleRodswap() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (!rodswapEnabled && wardrobeSwapEnabled) { wardrobeSwapEnabled = false; client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Wardrobe Swap disabled"), false); }
        rodswapEnabled = !rodswapEnabled;
        if (rodswapEnabled) {
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §aRodswap: §aENABLED"), false);
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §e⚠ MAKE SURE YOU HAVE WARDROBE SWAP DISABLED IN TAUNAHI"), false);
            writePestTriggers("rodswap");
        } else { client.player.sendMessage(Text.literal("§c§lTaun+++ >> §cRodswap: §cDISABLED"), false); writePestTriggers("none"); }
        saveSettings();
    }

    public static void toggleRosedrag() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        rosedragEnabled = !rosedragEnabled;
        saveSettings();
        writePestTriggers("rodswap");
        client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Rosedrag mode: " + (rosedragEnabled ? "§aENABLED" : "§cDISABLED")), false);
    }

    public static boolean isRosedragEnabled() { return rosedragEnabled; }

    public static void toggleWardrobeSwap() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (!wardrobeSwapEnabled && rodswapEnabled) { rodswapEnabled = false; client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Rodswap disabled"), false); }
        wardrobeSwapEnabled = !wardrobeSwapEnabled;
        if (wardrobeSwapEnabled) {
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §aWardrobe Swap: §aENABLED"), false);
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §e⚠ MAKE SURE TO ENABLE WARDROBE SWAP IN TAUNAHI"), false);
            writePestTriggers("wdswap");
        } else { client.player.sendMessage(Text.literal("§c§lTaun+++ >> §cWardrobe Swap: §cDISABLED"), false); writePestTriggers("none"); }
        saveSettings();
    }

    public static void toggleEtherwarp() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        etherwarpEnabled = !etherwarpEnabled;
        client.player.sendMessage(Text.literal("§c§lTaun+++ >> §" + (etherwarpEnabled ? "aEtherwarp: §aENABLED" : "cEtherwarp: §cDISABLED")), false);
        writePestTriggers(rodswapEnabled ? "rodswap" : wardrobeSwapEnabled ? "wdswap" : "none");
        saveSettings();
    }

    public static void toggleEqSwap() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        eqSwapEnabled = !eqSwapEnabled;
        client.player.sendMessage(Text.literal("§c§lTaun+++ >> §" + (eqSwapEnabled ? "aEquipment Swap: §aENABLED" : "cEquipment Swap: §cDISABLED")), false);
        writePestTriggers(rodswapEnabled ? "rodswap" : wardrobeSwapEnabled ? "wdswap" : "none");
        saveSettings();
    }

    public static void toggleZorro() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        zorroEnabled = !zorroEnabled;
        saveSettings();
        client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Zorro's Cape during Jacob: " + (zorroEnabled ? "§aENABLED" : "§cDISABLED")), false);
        if (zorroEnabled) client.player.sendMessage(Text.literal("§c§lTaun+++ >> §e⚠ Make sure you give Jacob's Event enough priority in tablist"), false);
    }

    public static void toggleDebug() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        debugEnabled = !debugEnabled;
        client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Debug messages: " + (debugEnabled ? "§aENABLED" : "§cDISABLED")), false);
    }

    public static void toggleGeorgeSlugSell() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        georgeSlugSellEnabled = !georgeSlugSellEnabled;
        saveSettings();
        client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7George slug sell: " + (georgeSlugSellEnabled ? "§aENABLED" : "§cDISABLED")), false);
    }

    public static void toggleExtraSell() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        boosterCookieEnabled = !boosterCookieEnabled;
        saveSettings();
        client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Extra sell (overclocker etc): " + (boosterCookieEnabled ? "§aENABLED" : "§cDISABLED")), false);
    }

    public static void setRotateSpeed(long ms) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (ms < 50 || ms > 5000) { client.player.sendMessage(Text.literal("§c§lTaun+++ >> §cInvalid value. Must be between 50 and 5000ms."), false); return; }
        rotateSpeedMs = ms;
        saveSettings();
        client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Etherwarp rotate speed set to §e" + ms + "ms"), false);
    }

    public static boolean isRodswapEnabled() { return rodswapEnabled; }
    public static boolean isWardrobeSwapEnabled() { return wardrobeSwapEnabled; }
    public static boolean isEtherwarpEnabled() { return etherwarpEnabled; }
    public static boolean isEqSwapEnabled() { return eqSwapEnabled; }
    public static boolean isDebugEnabled() { return debugEnabled; }
    public static long getRotateSpeedMs() { return rotateSpeedMs; }


    // ── Cobalt GUI integration getters ────────────────────────────────────────
    public static boolean isChatTriggersEnabled()  { return chatTriggersEnabled; }
    public static boolean isCoordTriggersEnabled() { return coordTriggersEnabled; }
    public static boolean isDynamicRestEnabled()   { return dynamicRestEnabled; }
    public static int getRestScriptingTime()       { return restScriptingTime; }
    public static int getRestScriptingTimeOffset() { return restScriptingTimeOffset; }
    public static int getRestBreakTime()           { return restBreakTime; }
    public static void showToggleStatus() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Chat Triggers: " + (chatTriggersEnabled ? "§aENABLED" : "§cDISABLED")), false);
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Coordinate Triggers: " + (coordTriggersEnabled ? "§aENABLED" : "§cDISABLED")), false);
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Rod Swap: " + (rodswapEnabled ? "§aENABLED" : "§cDISABLED")), false);
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Wardrobe Swap: " + (wardrobeSwapEnabled ? "§aENABLED" : "§cDISABLED")), false);
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Etherwarp: " + (etherwarpEnabled ? "§aENABLED" : "§cDISABLED")), false);
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Equipment Swap: " + (eqSwapEnabled ? "§aENABLED" : "§cDISABLED")), false);
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Rotate Speed: §e" + rotateSpeedMs + "ms"), false);
        }
    }

    public static void addCoordinateTrigger(double x, double y, double z, double radius, String command) {
        coordinateTriggers.add(new CoordinateTrigger(x, y, z, radius, command));
        saveCoordinateTriggers();
    }

    public static void listCoordinateTriggers() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (coordinateTriggers.isEmpty()) { client.player.sendMessage(Text.literal("§c§lTaun+++ >> §cNo coordinate triggers configured"), false); return; }
        client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Coordinate Triggers:"), false);
        for (int i = 0; i < coordinateTriggers.size(); i++) {
            CoordinateTrigger t = coordinateTriggers.get(i);
            client.player.sendMessage(Text.literal(String.format("§7%d. §e(%.2f, %.2f, %.2f) §7radius §e%.2f §7-> §a%s", i + 1, t.x, t.y, t.z, t.radius, t.command)), false);
        }
    }

    public static void removeCoordinateTrigger(int index) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (index < 0 || index >= coordinateTriggers.size()) { if (client.player != null) client.player.sendMessage(Text.literal("§c§lTaun+++ >> §cInvalid index"), false); return; }
        coordinateTriggers.remove(index);
        saveCoordinateTriggers();
    }

    private static void checkCoordinates(net.minecraft.client.network.ClientPlayerEntity player) {
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        long now = System.currentTimeMillis();
        List<CoordinateTrigger> matching = new ArrayList<>();
        for (CoordinateTrigger t : coordinateTriggers) {
            if (t.isInRange(px, py, pz) && !t.isOnCooldown()) {
                Long last = globalTriggerCooldowns.get(t.getKey());
                if (last == null || (now - last) >= GLOBAL_TRIGGER_COOLDOWN) matching.add(t);
            }
        }
        for (int i = 0; i < matching.size(); i++) {
            final CoordinateTrigger t = matching.get(i);
            final long delay = t.getDelayMs() + (i * 100L);
            globalTriggerCooldowns.put(t.getKey(), now + delay);
            t.triggerCooldown();
            new Thread(() -> { try { Thread.sleep(delay); executeCommand(t.command); } catch (InterruptedException e) {} }).start();
        }
    }

    private static void checkPestCooldown(MinecraftClient client) {
        if (client.player == null || client.player.networkHandler == null) return;
        boolean inGrace = worldJoinTime > 0 && (System.currentTimeMillis() - worldJoinTime) < WORLD_JOIN_GRACE_MS;
        var entries = client.player.networkHandler.getPlayerList();
        if (entries == null) return;
        boolean ready = false; String cdText = null;
        for (var e : entries) {
            if (e.getDisplayName() == null) continue;
            String t = e.getDisplayName().getString();
            if (t.toLowerCase().contains("cooldown")) cdText = t;
            if (t.contains("Cooldown:") && t.contains("READY")) { ready = true; break; }
        }
        if (cdText != null && !cdText.equals(lastPestCooldownText)) lastPestCooldownText = cdText;

        // Jacob's Contest tablist detection
        jacobContestActive = false;
        jacobTimerSeconds = -1;
        java.util.List<net.minecraft.client.network.PlayerListEntry> entryList = new java.util.ArrayList<>(entries);
        for (int ei = 0; ei < entryList.size(); ei++) {
            if (entryList.get(ei).getDisplayName() == null) continue;
            String jText = entryList.get(ei).getDisplayName().getString().replaceAll("§.", "");
            if (jText.contains("Jacob's Contest:")) {
                // Active if the entry contains a time remaining pattern like "10m left", "5m 30s left", "30s left"
                jacobContestActive = jText.matches(".*\\d+m.*left.*") || jText.matches(".*\\d+s.*left.*");
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+)m\\s*(\\d+)s|(\\d+)m|(\\d+)s")
                    .matcher(jText);
                if (m.find()) {
                    if (m.group(1) != null && m.group(2) != null)
                        jacobTimerSeconds = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
                    else if (m.group(3) != null)
                        jacobTimerSeconds = Integer.parseInt(m.group(3)) * 60;
                    else if (m.group(4) != null)
                        jacobTimerSeconds = Integer.parseInt(m.group(4));
                }
                break;
            }
        }
        if (!eqPestCdTriggers.isEmpty() && eqSwapEnabled && cdText != null && !ready && !inGrace) {
            int sec = parseCooldownSeconds(cdText);
            if (sec >= 160 && sec <= 170 && (System.currentTimeMillis() - lastEqSwapFireTime) > EQ_SWAP_COOLDOWN_MS) {
                lastEqSwapFireTime = System.currentTimeMillis();
                if (isFarming) for (PestCdTrigger t : eqPestCdTriggers) firePestCdTrigger(t);
                else { eqSwapPending = true; if (debugEnabled) client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Queuing EQSwap..."), false); }
            }
        }
        if (!eqPestCdWdTriggers.isEmpty() && cdText != null && !ready && !inGrace) {
            int sec = parseCooldownSeconds(cdText);
            if (sec >= 170 && sec <= 180 && (System.currentTimeMillis() - lastEqSwapFireTime) > EQ_SWAP_COOLDOWN_MS) {
                lastEqSwapFireTime = System.currentTimeMillis();
                if (isFarming) for (PestCdTrigger t : eqPestCdWdTriggers) firePestCdTrigger(t);
                else { eqSwapPending = true; if (debugEnabled) client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Queuing WD EQSwap..."), false); }
            }
        }
        long now = System.currentTimeMillis();
        if (ready && !lastPestCooldownReady && !inGrace && (now - lastPestReadyTime) > PEST_READY_COOLDOWN) {
            lastPestReadyTime = now;
            for (PestCdTrigger t : pestCdTriggers) firePestCdTrigger(t);
            if (pestCdTriggers.isEmpty() && rodswapEnabled && !eqSwapEnabled) triggerRodSwap();
        }
        lastPestCooldownReady = ready;
    }

    private static void firePestCdTrigger(PestCdTrigger trigger) {
        new Thread(() -> {
            try {
                if (!trigger.actions.isEmpty()) {
                    executeActionSequence(trigger.actions, trigger.blockInputs, trigger.blockInputsDelay);
                } else {
                    if (trigger.delayMs > 0) Thread.sleep(trigger.delayMs);
                    if (!trigger.command.isEmpty()) executeCommand(trigger.command);
                    for (ChatTrigger.BlockedCommand b : trigger.blockedCommands) activateCommandBlock(b.command, b.durationSeconds);
                    if (!trigger.keybindActions.isEmpty()) {
                        if (trigger.blockInputs && trigger.blockInputsDelay > 0) Thread.sleep(trigger.blockInputsDelay);
                        if (trigger.blockInputs) blockingInputs = true;
                        for (ChatTrigger.KeybindAction a : trigger.keybindActions) { Thread.sleep(applyRandomDelay(a.delayMs)); pressKey(a.key, a.originalKey); }
                        if (trigger.blockInputs) { Thread.sleep(50); blockingInputs = false; }
                    }
                }
                for (ChatTrigger.BlockedCommand b : trigger.blockedCommands) activateCommandBlock(b.command, b.durationSeconds);
            } catch (InterruptedException e) { if (trigger.blockInputs) blockingInputs = false; }
        }).start();
    }

    private static void checkPestAlive(MinecraftClient client) {
        if (pestAliveTriggers.isEmpty() || client.player == null || client.player.networkHandler == null) return;
        boolean inGrace = worldJoinTime > 0 && (System.currentTimeMillis() - worldJoinTime) < WORLD_JOIN_GRACE_MS;
        var entries = client.player.networkHandler.getPlayerList();
        if (entries == null) return;
        int count = -1; String aliveText = null;
        for (var e : entries) {
            if (e.getDisplayName() == null) continue;
            String t = e.getDisplayName().getString();
            if (t.toLowerCase().contains("alive")) {
                aliveText = t;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*$").matcher(t.trim());
                if (m.find()) { try { count = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {} }
                break;
            }
        }
        if (aliveText != null && !aliveText.equals(lastPestAliveText)) lastPestAliveText = aliveText;
        if (count == -1) { lastPestAliveCount = -1; return; }
        int prev = lastPestAliveCount; lastPestAliveCount = count;
        if (prev == -1 || inGrace) return;
        for (PestAliveTrigger t : pestAliveTriggers) { if (prev < t.threshold && count >= t.threshold) firePestAliveTrigger(t); }
    }

    private static void firePestAliveTrigger(PestAliveTrigger trigger) {
        new Thread(() -> {
            try {
                if (!trigger.actions.isEmpty()) {
                    executeActionSequence(trigger.actions, trigger.blockInputs, trigger.blockInputsDelay);
                } else {
                    if (trigger.delayMs > 0) Thread.sleep(trigger.delayMs);
                    if (!trigger.command.isEmpty()) executeCommand(trigger.command);
                    for (ChatTrigger.BlockedCommand b : trigger.blockedCommands) activateCommandBlock(b.command, b.durationSeconds);
                    if (!trigger.keybindActions.isEmpty()) {
                        if (trigger.blockInputs && trigger.blockInputsDelay > 0) Thread.sleep(trigger.blockInputsDelay);
                        if (trigger.blockInputs) blockingInputs = true;
                        for (ChatTrigger.KeybindAction a : trigger.keybindActions) { Thread.sleep(applyRandomDelay(a.delayMs)); pressKey(a.key, a.originalKey); }
                        if (trigger.blockInputs) { Thread.sleep(50); blockingInputs = false; }
                    }
                }
                for (ChatTrigger.BlockedCommand b : trigger.blockedCommands) activateCommandBlock(b.command, b.durationSeconds);
            } catch (InterruptedException e) { if (trigger.blockInputs) blockingInputs = false; }
        }).start();
    }

    private static void triggerRodSwap() {
        new Thread(() -> {
            try {
                int rodSlot = findRodSlotInHotbar(); int farmingSlot = findFarmingToolSlotInHotbar();
                if (rodSlot == -1 || farmingSlot == -1) return;
                MinecraftClient mc = MinecraftClient.getInstance();
                blockingInputs = true;
                Thread.sleep(250);
                pressKey(String.valueOf(rodSlot), "@ROD_SLOT");
                Thread.sleep(125);
                pressKey("rmb", "rmb");
                Thread.sleep(100);
                pressKey(String.valueOf(farmingSlot), "@FARMING_TOOL_SLOT");
                Thread.sleep(50);
                blockingInputs = false;
            } catch (InterruptedException e) { blockingInputs = false; }
        }).start();
    }

    private static void performRodSwap() throws InterruptedException {
        int rodSlot = findRodSlotInHotbar(); int farmingSlot = findFarmingToolSlotInHotbar();
        if (rodSlot == -1 || farmingSlot == -1) return;
        blockingInputs = true;
        try {
            Thread.sleep(250);
            pressKey(String.valueOf(rodSlot), "@ROD_SLOT");
            Thread.sleep(275);
            pressKey("rmb", "rmb");
            Thread.sleep(250);
            pressKey(String.valueOf(farmingSlot), "@FARMING_TOOL_SLOT");
            Thread.sleep(200);
        } finally { blockingInputs = false; }
    }

    // ── Abiphone / George sell ────────────────────────────────────────────────

    /**
     * Full Abiphone → George → sell slug sequence.
     * Called 500ms after "Auto sell finished" is detected in chat.
     *
     * Flow:
     *   1. Switch to abiphoneSlot, right-click to open Abiphone GUI
     *   2. Scan contacts for "George", click him
     *   3. Wait for "RING" to appear in chat (flag set by chat listener)
     *   4. Wait 5 more seconds for the call/sell GUI to open
     *   5. Scan all slots for item containing "[Lvl 1] Slug" → click it
     *   6. Press Escape to close the GUI
     */
    /** Scans hotbar slots 1-9 for any item whose name contains "abiphone" (case-insensitive).
     *  Returns the 1-based slot number, or -1 if not found. */
    private static int findAbiphoneSlotInHotbar() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            var stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getName().getString().toLowerCase().contains("abiphone")) {
                return i + 1;
            }
        }
        return -1;
    }

    private static void triggerAbiphoneGeorgeCall() throws InterruptedException {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        // ── Step 1: resolve Abiphone slot (manual setting or auto-detect) ────
        int resolvedSlot = abiphoneSlot;
        if (resolvedSlot <= 0) {
            resolvedSlot = findAbiphoneSlotInHotbar();
            if (resolvedSlot == -1) {
                LOGGER.warn("[George] No Abiphone found in hotbar and abiphoneSlot not set — aborting");
                if (mc.player != null)
                    mc.player.sendMessage(Text.literal("§c§lTaun+++ >> §cNo Abiphone found in hotbar! Set it with /pest abiphoneslot <1-9> or hold it."), false);
                return;
            }
            if (debugEnabled)
                mc.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Auto-detected Abiphone at slot " + resolvedSlot), false);
        }

        // Switch to Abiphone slot and right-click to open GUI
        pressKey(String.valueOf(resolvedSlot), String.valueOf(resolvedSlot));
        Thread.sleep(300);

        // Right-click using interactItem — works even when no GUI is open, triggers item use
        java.util.concurrent.CountDownLatch clickLatch = new java.util.concurrent.CountDownLatch(1);
        mc.execute(() -> {
            try {
                if (mc.player != null && mc.interactionManager != null) {
                    mc.interactionManager.interactItem(mc.player, net.minecraft.util.Hand.MAIN_HAND);
                }
            } finally { clickLatch.countDown(); }
        });
        clickLatch.await();

        // Wait up to 5s for the Abiphone GUI to open
        long deadline = System.currentTimeMillis() + 5000;
        while (mc.currentScreen == null && System.currentTimeMillis() < deadline) Thread.sleep(50);
        if (mc.currentScreen == null) {
            LOGGER.warn("[George] Abiphone GUI did not open — aborting");
            return;
        }
        Thread.sleep(500); // let slots populate

        // ── Step 2: find "George" in the contact list and click ──────────────
        java.util.concurrent.atomic.AtomicInteger georgeSlot = new java.util.concurrent.atomic.AtomicInteger(-1);
        java.util.concurrent.CountDownLatch scanLatch = new java.util.concurrent.CountDownLatch(1);
        mc.execute(() -> {
            try {
                if (mc.player.currentScreenHandler != null) {
                    var slots = mc.player.currentScreenHandler.slots;
                    for (int i = 0; i < slots.size(); i++) {
                        var slot = slots.get(i);
                        if (!slot.hasStack()) continue;
                        String name = slot.getStack().getName().getString();
                        // Strip Minecraft colour codes before comparing
                        String stripped = name.replaceAll("§.", "");
                        if (stripped.trim().equalsIgnoreCase("George")) {
                            georgeSlot.set(i);
                            break;
                        }
                    }
                }
            } finally { scanLatch.countDown(); }
        });
        scanLatch.await();

        if (georgeSlot.get() == -1) {
            LOGGER.warn("[George] Could not find 'George' contact in Abiphone GUI — aborting");
            pressKey("escape", "escape");
            return;
        }

        if (debugEnabled) mc.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Found George at slot " + georgeSlot.get()), false);

        // Click George's contact slot
        final int gs = georgeSlot.get();
        mc.execute(() -> {
            if (mc.player.currentScreenHandler != null)
                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, gs, 0,
                    net.minecraft.screen.slot.SlotActionType.PICKUP, mc.player);
        });

        // ── Step 3: wait for first "RING" in chat ────────────────────────────
        // georgeRingDetected flag is set by the chat listener when it sees "RING"
        georgeRingDetected = false; // reset in case it was stale
        long ringDeadline = System.currentTimeMillis() + 15_000; // wait up to 15s
        while (!georgeRingDetected && System.currentTimeMillis() < ringDeadline) Thread.sleep(100);

        if (!georgeRingDetected) {
            LOGGER.warn("[George] Never saw RING in chat — call may have failed, aborting");
            pressKey("escape", "escape");
            georgeRingDetected = false;
            return;
        }

        // ── Step 4: wait 5 seconds after first RING for sell GUI to appear ───
        if (debugEnabled) mc.player.sendMessage(Text.literal("§c§lTaun+++ >> §7RING detected! Waiting 5s for George's sell GUI..."), false);
        Thread.sleep(5000);

        // Wait up to 5 more seconds for a GUI to be on screen (call GUI)
        deadline = System.currentTimeMillis() + 5000;
        while (mc.currentScreen == null && System.currentTimeMillis() < deadline) Thread.sleep(50);
        if (mc.currentScreen == null) {
            LOGGER.warn("[George] No GUI appeared after RING — aborting");
            georgeRingDetected = false;
            return;
        }
        Thread.sleep(300); // let slots populate

        // ── Step 5: scan for "[Lvl 1] Slug" and click it ────────────────────
        java.util.concurrent.atomic.AtomicInteger slugSlot = new java.util.concurrent.atomic.AtomicInteger(-1);
        java.util.concurrent.atomic.AtomicInteger fallbackSlot = new java.util.concurrent.atomic.AtomicInteger(-1);
        java.util.concurrent.CountDownLatch slugLatch = new java.util.concurrent.CountDownLatch(1);
        mc.execute(() -> {
            try {
                if (mc.player.currentScreenHandler != null) {
                    var slots = mc.player.currentScreenHandler.slots;
                    for (int i = 0; i < slots.size(); i++) {
                        var slot = slots.get(i);
                        if (!slot.hasStack()) continue;
                        String rawName = slot.getStack().getName().getString();
                        String name = rawName.replaceAll("§.", "").toLowerCase();

                        // Primary match: slot name contains "[lvl 1] slug"
                        if (name.contains("[lvl 1] slug")) {
                            slugSlot.set(i);
                            break;
                        }

                        // Fallback: first slot that isn't a known filler
                        if (fallbackSlot.get() == -1) {
                            boolean isFiller = name.isEmpty()
                                || name.contains("stained glass")
                                || name.contains("barrier")
                                || name.contains("air")
                                || name.equals("air");
                            if (!isFiller) fallbackSlot.set(i);
                        }
                    }
                }
            } finally { slugLatch.countDown(); }
        });
        slugLatch.await();

        int clickSlot = slugSlot.get() != -1 ? slugSlot.get() : fallbackSlot.get();
        if (clickSlot == -1) {
            LOGGER.warn("[George] Could not find slug slot or any fallback slot in call GUI");
            pressKey("escape", "escape");
            georgeRingDetected = false;
            return;
        }

        if (debugEnabled) mc.player.sendMessage(Text.literal(
            "§c§lTaun+++ >> §7Shift-clicking slug slot " + clickSlot + (slugSlot.get() != -1 ? " ([Lvl 1] Slug match)" : " (fallback)")), false);

        // Shift-click the slug item
        final int cs = clickSlot;
        mc.execute(() -> {
            if (mc.player.currentScreenHandler != null)
                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, cs, 0,
                    net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
        });
        Thread.sleep(400);

        // ── Step 6: scan for the green clay (lime/green terracotta or stained glass confirm button)
        //    and click it twice with a short delay between clicks ──────────────
        java.util.concurrent.atomic.AtomicInteger greenSlot = new java.util.concurrent.atomic.AtomicInteger(-1);
        java.util.concurrent.CountDownLatch greenLatch = new java.util.concurrent.CountDownLatch(1);
        mc.execute(() -> {
            try {
                if (mc.player.currentScreenHandler != null) {
                    var slots = mc.player.currentScreenHandler.slots;
                    for (int i = 0; i < slots.size(); i++) {
                        var slot = slots.get(i);
                        if (!slot.hasStack()) continue;
                        String itemId = net.minecraft.registry.Registries.ITEM
                            .getId(slot.getStack().getItem()).toString().toLowerCase();
                        String itemName = slot.getStack().getName().getString()
                            .replaceAll("§.", "").toLowerCase();
                        // Match lime/green clay, terracotta, or stained glass confirm buttons
                        boolean isGreen = itemId.contains("lime") || itemId.contains("green")
                            || itemName.contains("lime") || itemName.contains("green")
                            || itemName.contains("confirm") || itemName.contains("yes");
                        if (isGreen) {
                            greenSlot.set(i);
                            break;
                        }
                    }
                }
            } finally { greenLatch.countDown(); }
        });
        greenLatch.await();

        if (greenSlot.get() == -1) {
            LOGGER.warn("[George] Could not find green clay confirm button — closing GUI");
            pressKey("escape", "escape");
            georgeRingDetected = false;
            return;
        }

        if (debugEnabled) mc.player.sendMessage(Text.literal(
            "§c§lTaun+++ >> §7Clicking green clay at slot " + greenSlot.get()), false);

        // First click on green clay
        final int gs2 = greenSlot.get();
        mc.execute(() -> {
            if (mc.player.currentScreenHandler != null)
                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, gs2, 0,
                    net.minecraft.screen.slot.SlotActionType.PICKUP, mc.player);
        });
        Thread.sleep(300);

        // Second click on green clay
        mc.execute(() -> {
            if (mc.player.currentScreenHandler != null)
                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, gs2, 0,
                    net.minecraft.screen.slot.SlotActionType.PICKUP, mc.player);
        });
        Thread.sleep(300);

        // ── Step 7: close the GUI ─────────────────────────────────────────────
        pressKey("escape", "escape");
        georgeRingDetected = false;

        if (debugEnabled) mc.player.sendMessage(Text.literal("§c§lTaun+++ >> §aGeorge sell sequence complete!"), false);
        LOGGER.info("[George] Sell sequence complete.");
    }

    /** Sets the Abiphone hotbar slot and saves settings. */
    public static void setAbiphoneSlot(int slot) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (slot < 1 || slot > 9) {
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §cSlot must be 1-9"), false);
            return;
        }
        abiphoneSlot = slot;
        saveSettings();
        client.player.sendMessage(Text.literal("§c§lTaun+++ >> §aAbiphone slot set to §e" + slot), false);
    }

    public static int getAbiphoneSlot() { return abiphoneSlot; }

    /** Auto-detects Abiphone in hotbar and saves it, or clears if already set (toggle). */
    public static void autoDetectAndToggleAbiphone() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        // If already configured, clear it
        if (abiphoneSlot > 0) {
            abiphoneSlot = 0;
            saveSettings();
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Abiphone slot cleared (auto-detect will be used)"), false);
            return;
        }
        int detected = findAbiphoneSlotInHotbar();
        if (detected == -1) {
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §cNo Abiphone found in hotbar! Hold it and try again, or use /pest abiphoneslot <1-9>"), false);
            return;
        }
        abiphoneSlot = detected;
        saveSettings();
        client.player.sendMessage(Text.literal("§c§lTaun+++ >> §aAbiphone auto-detected in slot §e" + detected), false);
    }

    /**
     * Parses a flexible time string into minutes.
     * Supports: "2h", "2 hours", "2.5h", "2.5 hours", "30m", "30 minutes", "30", plain numbers = minutes.
     * Returns -1 if unparseable.
     */
    public static int parseDynaRestTime(String input) {
        input = input.trim().toLowerCase().replaceAll("\s+", "");
        try {
            // Hours: 2h, 2hours, 2.5h, 2.5hours
            if (input.endsWith("hours") || input.endsWith("hour") || input.endsWith("h")) {
                String num = input.replaceAll("[a-z]", "");
                double hours = Double.parseDouble(num);
                return (int) Math.round(hours * 60);
            }
            // Minutes: 30m, 30minutes, 30min
            if (input.endsWith("minutes") || input.endsWith("minute") || input.endsWith("min") || input.endsWith("m")) {
                String num = input.replaceAll("[a-z]", "");
                return (int) Math.round(Double.parseDouble(num));
            }
            // Plain number = minutes
            return (int) Math.round(Double.parseDouble(input));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void loadCoordinateTriggers() {
        try {
            Files.createDirectories(coordConfigPath.getParent());
            if (!Files.exists(coordConfigPath)) createDefaultCoordinateConfig();
            List<String> lines = Files.readAllLines(coordConfigPath);
            coordinateTriggers.clear();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                try {
                    String[] parts = line.split("=", 2);
                    if (parts.length != 2) continue;
                    String[] coords = parts[0].trim().split(",");
                    if (coords.length != 4 && coords.length != 5) continue;
                    double x = Double.parseDouble(coords[0].trim()), y = Double.parseDouble(coords[1].trim()), z = Double.parseDouble(coords[2].trim()), r = Double.parseDouble(coords[3].trim());
                    long d = coords.length == 5 ? Long.parseLong(coords[4].trim()) : 0;
                    coordinateTriggers.add(new CoordinateTrigger(x, y, z, r, parts[1].trim().replace("\"", ""), d));
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException e) { LOGGER.error("Failed to load coordinate triggers", e); }
    }

    private static void saveCoordinateTriggers() {
        try {
            List<String> lines = new ArrayList<>(List.of("# Coordinate Trigger Configuration", "# Format: x,y,z,radius = /command", ""));
            for (CoordinateTrigger t : coordinateTriggers) {
                if (t.getDelayMs() > 0) lines.add(String.format("%.2f,%.2f,%.2f,%.2f,%d = %s", t.x, t.y, t.z, t.radius, t.getDelayMs(), t.command));
                else lines.add(String.format("%.2f,%.2f,%.2f,%.2f = %s", t.x, t.y, t.z, t.radius, t.command));
            }
            Files.write(coordConfigPath, lines);
        } catch (IOException e) { LOGGER.error("Failed to save coordinate triggers", e); }
    }

    private static void createDefaultCoordinateConfig() throws IOException {
        Files.write(coordConfigPath, List.of("# Taun+++ Coordinate Triggers Configuration", "# Add coordinate triggers using /pest setspawn and /pest setend", ""));
    }

    // ── Inner classes ─────────────────────────────────────────────────────────

    private static class ChatTrigger {
        private final String triggerText;
        private final String originalTriggerText;
        private final String groupKey; // shared across all triggers from the same TRIGGER: line
        private final String command;
        private final long delayMs;
        private final List<KeybindAction> keybindActions;
        private final boolean blockInputs;
        private final long blockInputsDelay;
        private final List<BlockedCommand> blockedCommands;
        private final boolean waitOnGuiClosure;
        private final long waitOnGuiClosureDelay;
        private final boolean waitOnGuiOpen;
        private final long waitOnGuiOpenDelay;

        private static class BlockedCommand {
            final String command; final int durationSeconds;
            BlockedCommand(String command, int durationSeconds) { this.command = command; this.durationSeconds = durationSeconds; }
        }

        static class TriggerAction {
            enum Type { COMMAND, PRESS, HOLD, UNHOLD, ROTATE_TO, ETHERWARP_TO, EQSWAP, RODSWAP, IFJACOB_FALSE, IFJACOB_TRUE, WAITFORCHAT, WAITFORJACOBTIMER, IFCROPFEVER_SKIP_GUI, RETRYUNTILSKYBLOCK, WAITONGUIOPEN }
            final Type type; final String value; final String originalKey; final long delayMs;
            final float yawMin, yawMax, pitchMin, pitchMax, landPitchMin, landPitchMax; final long rotateDurationMs;
            TriggerAction(Type type, String value, String originalKey, long delayMs) { this.type = type; this.value = value; this.originalKey = originalKey; this.delayMs = delayMs; yawMin = yawMax = pitchMin = pitchMax = landPitchMin = landPitchMax = 0; rotateDurationMs = 0; }
            TriggerAction(float yawMin, float yawMax, float pitchMin, float pitchMax, long delayMs, long rotateDurationMs) { type = Type.ROTATE_TO; value = ""; originalKey = ""; this.delayMs = delayMs; this.yawMin = yawMin; this.yawMax = yawMax; this.pitchMin = pitchMin; this.pitchMax = pitchMax; this.rotateDurationMs = rotateDurationMs > 0 ? rotateDurationMs : 250; landPitchMin = landPitchMax = 0; }
            TriggerAction(float yawMin, float yawMax, float pitchMin, float pitchMax, float landPitchMin, float landPitchMax, long delayMs) { type = Type.ETHERWARP_TO; value = "etherwarp"; originalKey = ""; this.delayMs = delayMs; this.yawMin = yawMin; this.yawMax = yawMax; this.pitchMin = pitchMin; this.pitchMax = pitchMax; this.landPitchMin = landPitchMin; this.landPitchMax = landPitchMax; rotateDurationMs = 200; }
            TriggerAction(String swapTarget, long delayMs) { type = Type.EQSWAP; value = swapTarget; originalKey = ""; this.delayMs = delayMs; yawMin = yawMax = pitchMin = pitchMax = landPitchMin = landPitchMax = 0; rotateDurationMs = 0; }
        }

        final List<TriggerAction> actions;

        private static class KeybindAction {
            final long delayMs; final String key; final String originalKey;
            KeybindAction(long delayMs, String key) { this.delayMs = delayMs; this.key = key; this.originalKey = key; }
            KeybindAction(long delayMs, String key, String originalKey) { this.delayMs = delayMs; this.key = key; this.originalKey = originalKey; }
        }

        public ChatTrigger(String triggerText, String command) { this(triggerText, command, 0, new ArrayList<>(), false, 0, new ArrayList<>()); }
        public ChatTrigger(String triggerText, String command, long delayMs) { this(triggerText, command, delayMs, new ArrayList<>(), false, 0, new ArrayList<>()); }
        public ChatTrigger(String triggerText, String command, long delayMs, String keybind) { this(triggerText, command, delayMs, keybind, 100, false, 0, new ArrayList<>()); }
        public ChatTrigger(String triggerText, String command, long delayMs, String keybind, long keybindDelayMs) { this(triggerText, command, delayMs, keybind, keybindDelayMs, false, 0, new ArrayList<>()); }
        public ChatTrigger(String triggerText, String command, long delayMs, String keybind, long keybindDelayMs, boolean blockInputs) { this(triggerText, command, delayMs, keybind, keybindDelayMs, blockInputs, 0, new ArrayList<>()); }
        public ChatTrigger(String triggerText, String command, long delayMs, String keybind, long keybindDelayMs, boolean blockInputs, long blockInputsDelay) { this(triggerText, command, delayMs, keybind, keybindDelayMs, blockInputs, blockInputsDelay, new ArrayList<>()); }
        public ChatTrigger(String triggerText, String command, long delayMs, String keybind, long keybindDelayMs, boolean blockInputs, long blockInputsDelay, List<BlockedCommand> blockedCommands) {
            if (triggerText == null || triggerText.trim().isEmpty()) throw new IllegalArgumentException("Trigger text cannot be empty");
            this.originalTriggerText = triggerText; this.triggerText = triggerText.toLowerCase(); this.groupKey = triggerText.toLowerCase(); this.command = command; this.delayMs = delayMs;
            this.blockInputs = blockInputs; this.blockInputsDelay = blockInputsDelay; this.blockedCommands = blockedCommands != null ? blockedCommands : new ArrayList<>();
            this.waitOnGuiClosure = false; this.waitOnGuiClosureDelay = 0; this.waitOnGuiOpen = false; this.waitOnGuiOpenDelay = 0;
            this.keybindActions = new ArrayList<>(); this.actions = new ArrayList<>();
            if (keybind != null && !keybind.isEmpty()) this.keybindActions.add(new KeybindAction(keybindDelayMs, keybind));
        }
        public ChatTrigger(String triggerText, String command, long delayMs, List<KeybindAction> keybindActions) { this(triggerText, command, delayMs, keybindActions, false, 0, new ArrayList<>()); }
        public ChatTrigger(String triggerText, String command, long delayMs, List<KeybindAction> keybindActions, boolean blockInputs) { this(triggerText, command, delayMs, keybindActions, blockInputs, 0, new ArrayList<>()); }
        public ChatTrigger(String triggerText, String command, long delayMs, List<KeybindAction> keybindActions, boolean blockInputs, long blockInputsDelay, List<BlockedCommand> blockedCommands) { this(triggerText, command, delayMs, keybindActions, blockInputs, blockInputsDelay, blockedCommands, false, 0); }
        public ChatTrigger(String triggerText, String command, long delayMs, List<KeybindAction> keybindActions, boolean blockInputs, long blockInputsDelay, List<BlockedCommand> blockedCommands, boolean waitOnGuiClosure, long waitOnGuiClosureDelay) { this(triggerText, command, delayMs, keybindActions, blockInputs, blockInputsDelay, blockedCommands, waitOnGuiClosure, waitOnGuiClosureDelay, false, 0); }
        public ChatTrigger(String triggerText, String command, long delayMs, List<KeybindAction> keybindActions, boolean blockInputs, long blockInputsDelay, List<BlockedCommand> blockedCommands, boolean waitOnGuiClosure, long waitOnGuiClosureDelay, boolean waitOnGuiOpen) { this(triggerText, command, delayMs, keybindActions, blockInputs, blockInputsDelay, blockedCommands, waitOnGuiClosure, waitOnGuiClosureDelay, waitOnGuiOpen, 0); }
        public ChatTrigger(String triggerText, String command, long delayMs, List<KeybindAction> keybindActions, boolean blockInputs, long blockInputsDelay, List<BlockedCommand> blockedCommands, boolean waitOnGuiClosure, long waitOnGuiClosureDelay, boolean waitOnGuiOpen, long waitOnGuiOpenDelay) { this(triggerText, triggerText, command, delayMs, keybindActions, blockInputs, blockInputsDelay, blockedCommands, waitOnGuiClosure, waitOnGuiClosureDelay, waitOnGuiOpen, waitOnGuiOpenDelay); }
        public ChatTrigger(String triggerText, String groupKey, String command, long delayMs, List<KeybindAction> keybindActions, boolean blockInputs, long blockInputsDelay, List<BlockedCommand> blockedCommands, boolean waitOnGuiClosure, long waitOnGuiClosureDelay, boolean waitOnGuiOpen, long waitOnGuiOpenDelay) {
            if (triggerText == null || triggerText.trim().isEmpty()) throw new IllegalArgumentException("Trigger text cannot be empty");
            this.originalTriggerText = triggerText; this.triggerText = triggerText.toLowerCase(); this.groupKey = (groupKey != null ? groupKey : triggerText).toLowerCase(); this.command = command; this.delayMs = delayMs;
            this.keybindActions = keybindActions; this.blockInputs = blockInputs; this.blockInputsDelay = blockInputsDelay;
            this.blockedCommands = blockedCommands != null ? blockedCommands : new ArrayList<>();
            this.waitOnGuiClosure = waitOnGuiClosure; this.waitOnGuiClosureDelay = waitOnGuiClosureDelay; this.waitOnGuiOpen = waitOnGuiOpen; this.waitOnGuiOpenDelay = waitOnGuiOpenDelay;
            this.actions = new ArrayList<>();
        }
        public List<TriggerAction> getActions() { return actions; }
        public boolean hasActions() { return !actions.isEmpty(); }
        public boolean matches(String message) { return message.toLowerCase().contains(triggerText); }
        public String getCommand() { return command; }
        public long getDelayMs() { return delayMs; }
        public List<KeybindAction> getKeybindActions() { return keybindActions; }
        public boolean hasKeybind() { return !keybindActions.isEmpty(); }
        public boolean shouldBlockInputs() { return blockInputs; }
        public long getBlockInputsDelay() { return blockInputsDelay; }
        public List<BlockedCommand> getBlockedCommands() { return blockedCommands; }
        public boolean hasBlockedCommands() { return !blockedCommands.isEmpty(); }
        public boolean shouldWaitOnGuiClosure() { return waitOnGuiClosure; }
        public long getWaitOnGuiClosureDelay() { return waitOnGuiClosureDelay; }
        public boolean shouldWaitOnGuiOpen() { return waitOnGuiOpen; }
        public long getWaitOnGuiOpenDelay() { return waitOnGuiOpenDelay; }
        public String getTriggerText() { return originalTriggerText; }
        public String getGroupKey() { return groupKey; }
    }

    private static class PestCdTrigger {
        final String command; final long delayMs; final List<ChatTrigger.KeybindAction> keybindActions;
        final boolean blockInputs; final long blockInputsDelay; final List<ChatTrigger.BlockedCommand> blockedCommands; final List<ChatTrigger.TriggerAction> actions;
        PestCdTrigger(String command, long delayMs, List<ChatTrigger.KeybindAction> keybindActions, boolean blockInputs, long blockInputsDelay, List<ChatTrigger.BlockedCommand> blockedCommands, List<ChatTrigger.TriggerAction> actions) {
            this.command = command; this.delayMs = delayMs; this.keybindActions = keybindActions; this.blockInputs = blockInputs; this.blockInputsDelay = blockInputsDelay;
            this.blockedCommands = blockedCommands != null ? blockedCommands : new ArrayList<>(); this.actions = actions != null ? actions : new ArrayList<>();
        }
    }

    private static class PestAliveTrigger {
        final int threshold; final String command; final long delayMs; final List<ChatTrigger.KeybindAction> keybindActions;
        final boolean blockInputs; final long blockInputsDelay; final List<ChatTrigger.BlockedCommand> blockedCommands; final List<ChatTrigger.TriggerAction> actions;
        PestAliveTrigger(int threshold, String command, long delayMs, List<ChatTrigger.KeybindAction> keybindActions, boolean blockInputs, long blockInputsDelay, List<ChatTrigger.BlockedCommand> blockedCommands, List<ChatTrigger.TriggerAction> actions) {
            this.threshold = threshold; this.command = command; this.delayMs = delayMs; this.keybindActions = keybindActions; this.blockInputs = blockInputs; this.blockInputsDelay = blockInputsDelay;
            this.blockedCommands = blockedCommands != null ? blockedCommands : new ArrayList<>(); this.actions = actions != null ? actions : new ArrayList<>();
        }
    }

    private static class CoordinateTrigger {
        final double x, y, z, radius; final String command; private final long delayMs;
        private long lastTriggered = 0; private static final long TRIGGER_COOLDOWN = 5000;
        CoordinateTrigger(double x, double y, double z, double radius, String command) { this(x, y, z, radius, command, 0); }
        CoordinateTrigger(double x, double y, double z, double radius, String command, long delayMs) { this.x = x; this.y = y; this.z = z; this.radius = radius; this.command = command; this.delayMs = delayMs; }
        boolean isInRange(double px, double py, double pz) { double dx = px-x, dy = py-y, dz = pz-z; return Math.sqrt(dx*dx+dy*dy+dz*dz) <= radius; }
        boolean isOnCooldown() { return (System.currentTimeMillis() - lastTriggered) < TRIGGER_COOLDOWN; }
        void triggerCooldown() { lastTriggered = System.currentTimeMillis(); }
        String getKey() { return String.format("%.2f_%.2f_%.2f_%.2f_%s", x, y, z, radius, command); }
        long getDelayMs() { return delayMs; }
    }

    // ── Setup wizard ──────────────────────────────────────────────────────────

    public static void startSetupWizard() {
        try { if (Files.exists(coordConfigPath)) { Files.writeString(coordConfigPath, ""); loadCoordinateTriggers(); } } catch (IOException ignored) {}
        chatTriggersEnabled = false; coordTriggersEnabled = false;
        setupWizardActive = true; setupStep = 0; setupData.clear();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§c§lTaun+++ Setup"), false);
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Type answers in chat (they won't be sent to server)"), false);
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Type §c'cancel'§7 anytime to exit"), false);
            showSetupStep();
        }
    }

    private static void showSetupStep() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        switch (setupStep) {
            case 0 -> {
                Map<String, Integer> detected = detectHotbarTools();
                if (detected.containsKey("rod")) { setupData.put("rod_slot", String.valueOf(detected.get("rod"))); client.player.sendMessage(Text.literal("§c§lTaun+++ >> §a[Step 1/7] §7Auto-detected §eROD§7 in slot §a" + detected.get("rod") + "§7. Type another slot or §ayes§7 to confirm"), false); }
                else client.player.sendMessage(Text.literal("§c§lTaun+++ >> §a[Step 1/7] §7Which slot is your §eROD§7? (1-9)"), false);
            }
            case 1 -> {
                Map<String, Integer> detected = detectHotbarTools();
                if (detected.containsKey("farming_tool")) { setupData.put("farming_slot", String.valueOf(detected.get("farming_tool"))); client.player.sendMessage(Text.literal("§c§lTaun+++ >> §a[Step 2/7] §7Auto-detected §eFARMING TOOL§7 in slot §a" + detected.get("farming_tool") + "§7. Type another slot or §ayes§7 to confirm"), false); }
                else client.player.sendMessage(Text.literal("§c§lTaun+++ >> §a[Step 2/7] §7Which slot is your §eFARMING TOOL§7? (1-9)"), false);
            }
            case 2 -> {
                client.player.sendMessage(Text.literal("§c§lTaun+++ >> §a[Step 3/7] §7Swap method? §e§lrodswap §8/ §e§lwdswap §8/ §e§lnone"), false);
            }
            case 3 -> client.player.sendMessage(Text.literal("§c§lTaun+++ >> §a[Step 4/7] §7What is your §ePlot number§7? (e.g. §a11§7)"), false);
            case 4 -> { setupCommandExecuted = false; client.player.sendMessage(Text.literal("§c§lTaun+++ >> §a[Step 5/7] §7Go to your §eSpawn §7and run §a/pest setspawn"), false); }
            case 5 -> { setupCommandExecuted = false; client.player.sendMessage(Text.literal("§c§lTaun+++ >> §a[Step 6/7] §7Go to the §eEnd of your farm §7and run §a/pest setend"), false); }
            case 6 -> { setupCommandExecuted = false; client.player.sendMessage(Text.literal("§c§lTaun+++ >> §a[Step 7/7] §7Move §e3 blocks into the first lane §7and run §a/pest setspawntrigger"), false); }
        }
    }

    public static boolean handleSetupInput(String input) {
        if (!setupWizardActive) return false;
        input = input.trim().toLowerCase();
        if (input.equals("cancel")) { cancelSetup(); return true; }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return true;
        switch (setupStep) {
            case 0 -> {
                if ((input.equals("yes") || input.equals("y")) && setupData.containsKey("rod_slot")) { client.player.sendMessage(Text.literal("§c§lTaun+++ >> §a✓ Rod slot: " + setupData.get("rod_slot")), false); setupStep++; showSetupStep(); }
                else { try { int s = Integer.parseInt(input); if (s < 1 || s > 9) { client.player.sendMessage(Text.literal("§c§lTaun+++ >> §c✗ Must be 1-9"), false); return true; } setupData.put("rod_slot", String.valueOf(s)); setupStep++; showSetupStep(); } catch (NumberFormatException e) { client.player.sendMessage(Text.literal("§c§lTaun+++ >> §c✗ Enter a number 1-9 or yes/y"), false); } }
            }
            case 1 -> {
                if ((input.equals("yes") || input.equals("y")) && setupData.containsKey("farming_slot")) { client.player.sendMessage(Text.literal("§c§lTaun+++ >> §a✓ Farming slot: " + setupData.get("farming_slot")), false); setupStep++; showSetupStep(); }
                else { try { int s = Integer.parseInt(input); if (s < 1 || s > 9) { client.player.sendMessage(Text.literal("§c§lTaun+++ >> §c✗ Must be 1-9"), false); return true; } setupData.put("farming_slot", String.valueOf(s)); setupStep++; showSetupStep(); } catch (NumberFormatException e) { client.player.sendMessage(Text.literal("§c§lTaun+++ >> §c✗ Enter a number 1-9 or yes/y"), false); } }
            }
            case 2 -> {
                if (input.equals("rodswap") || input.equals("wdswap") || input.equals("none")) { setupData.put("swap_mode", input); client.player.sendMessage(Text.literal("§c§lTaun+++ >> §a✓ Swap mode: " + input), false); setupStep++; showSetupStep(); }
                else client.player.sendMessage(Text.literal("§c§lTaun+++ >> §c✗ Type rodswap, wdswap, or none"), false);
            }
            case 3 -> {
                try { int p = Integer.parseInt(input); if (p < 1 || p > 24) { client.player.sendMessage(Text.literal("§c§lTaun+++ >> §c✗ Must be 1-24"), false); return true; } setupData.put("plot_number", String.valueOf(p)); client.player.sendMessage(Text.literal("§c§lTaun+++ >> §a✓ Plot: " + p), false); setupStep++; showSetupStep(); } catch (NumberFormatException e) { client.player.sendMessage(Text.literal("§c§lTaun+++ >> §c✗ Enter a valid number"), false); }
            }
            case 4 -> client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Run §a/pest setspawn"), false);
            case 5 -> client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Run §a/pest setend"), false);
            case 6 -> client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Run §a/pest setspawntrigger"), false);
        }
        return true;
    }

    /** Builds the ETHERWARP_TO line from saved coords with ±10 offset, pitch clamped to -90. */
    private static String buildEtherwarpLine() {
        float yawMin = etherwarpYaw - 10f;
        float yawMax = etherwarpYaw + 10f;
        float pitchMin = Math.max(-90f, etherwarpPitch - 10f);
        float pitchMax = Math.max(-90f, etherwarpPitch + 10f);
        // format without trailing zeros
        java.util.function.Function<Float, String> fmt = v -> (v == Math.floor(v)) ? String.valueOf((int)(float)v) : String.valueOf(v);
        return "  ETHERWARP_TO: " + fmt.apply(yawMin) + " - " + fmt.apply(yawMax) + ", " + fmt.apply(pitchMin) + " - " + fmt.apply(pitchMax) + " after 100ms\n";
    }

    public static void startSetEtherwarpCoords() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        settingEtherwarpCoords = true;
        client.player.sendMessage(Text.literal("§c§lTaun+++ >> §eLook at your desired etherwarp target, then §7right-click §eto save the yaw + pitch"), false);
    }

    public static void captureEtherwarpCoordsIfPending() {
        if (!settingEtherwarpCoords) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        settingEtherwarpCoords = false;
        etherwarpYaw = client.player.getYaw();
        etherwarpPitch = client.player.getPitch();
        saveSettings();
        // Rewrite triggers with new coords
        String mode = rodswapEnabled ? "rodswap" : wardrobeSwapEnabled ? "wdswap" : "none";
        writePestTriggers(mode);
        float yawMin = etherwarpYaw - 10f, yawMax = etherwarpYaw + 10f;
        float pitchMin = Math.max(-90f, etherwarpPitch - 10f), pitchMax = Math.max(-90f, etherwarpPitch + 10f);
        client.player.sendMessage(Text.literal(
            "§c§lTaun+++ >> §aEtherwarp coords set! §7Yaw: " + String.format("%.1f", yawMin) + " - " + String.format("%.1f", yawMax) +
            "  Pitch: " + String.format("%.1f", pitchMin) + " - " + String.format("%.1f", pitchMax)), false);
    }

    private static void appendVisitorTriggers(StringBuilder t) {
        t.append("\n# visitor support (dont change unless buggy)\n");
        t.append("TRIGGER: \"script stopped. [Visitors]\"\n");
        t.append("  COMMAND: /setspawn\n").append("  COMMAND: .ez-stopscript after 50ms\n").append("  COMMAND: .ez-startscript misc:visitor after 50ms\n");
        t.append("TRIGGER: \"Visitor script stopped. [Finished]\"\n");
        t.append("  COMMAND: /warp garden\n").append("  COMMAND: .ez-startscript netherwart:1 after 50ms\n");
    }

    private static void appendServerShutdownTrigger(StringBuilder t) {
        t.append("\n# server shutdown safety (dont change unless buggy)\n");
        t.append("TRIGGER: \"server will restart soon\" or \"proxy is\"\n");
        t.append("  IFJACOB=FALSE\n");
        t.append("    COMMAND: .ez-stopscript\n");
        t.append("    COMMAND: /setspawn\n");
        t.append("    COMMAND: /lobby after 5s\n");
        t.append("    RETRYUNTILSKYBLOCK\n");
        t.append("    COMMAND: .ez-startscript netherwart:1\n");
        t.append("  IFJACOB=TRUE\n");
        t.append("    WAITFORJACOBTIMER: 15\n");
        t.append("    COMMAND: .ez-stopscript\n");
        t.append("    COMMAND: /setspawn\n");
        t.append("    COMMAND: /lobby after 3s\n");
        t.append("    RETRYUNTILSKYBLOCK\n");
        t.append("    COMMAND: .ez-startscript netherwart:1\n");
    }

    public static void writePestTriggers(String mode) {
        try {
            StringBuilder t = new StringBuilder();
            switch (mode) {
                case "rodswap" -> {
                    t.append("# rodswap config\n");
                    if (eqSwapEnabled) { t.append("EQPestCD:\n  COMMAND: .ez-stopscript\n  EQSWAP: PEST\n  RODSWAP\n  COMMAND: .ez-startscript netherwart:1 after 50ms\n"); }
                    else { t.append("PestCD:\n  COMMAND: .ez-stopscript\n  RODSWAP\n  COMMAND: .ez-startscript netherwart:1 after 50ms\n"); }
                    t.append("\nTRIGGER: \"spawned in\"\n  COMMAND: .ez-stopscript after 350ms\n");
                    if (eqSwapEnabled) t.append("  EQSWAP: BLOSSOM/LOTUS\n");
                    t.append("  COMMAND: /setspawn\n");
                    if (etherwarpEnabled) t.append(buildEtherwarpLine());
                    t.append("  COMMAND: .ez-startscript misc:pestCleaner\n\nTRIGGER: \"Pest Cleaner script stopped. [Finished]\"\n  COMMAND: /warp garden\n  HOLD: shift for 350ms\n");
                    if (!rosedragEnabled) t.append("  RODSWAP\n");
                    t.append("  COMMAND: .ez-startscript netherwart:1 after 100ms\n");
                    appendVisitorTriggers(t);
                    appendServerShutdownTrigger(t);
                }
                case "wdswap" -> {
                    t.append("# wardrobe swap config\n");
                    t.append("EQPestCDWD:\n  COMMAND: .ez-stopscript\n  EQSWAP: PEST\n  COMMAND: .ez-startscript netherwart:1 after 100ms\n\n");
                    t.append("TRIGGER: \"spawned in\"\n  WAITFORCHAT: \"script stopped. [Pests]\"\n  COMMAND: /setspawn\n  IFCROPFEVER: SKIP_GUI\n  WAITONGUIOPEN\n  COMMAND: .ez-stopscript\n");
                    if (eqSwapEnabled) t.append("  EQSWAP: BLOSSOM/LOTUS\n");
                    if (etherwarpEnabled) t.append(buildEtherwarpLine());
                    t.append("  COMMAND: .ez-startscript misc:pestCleaner\n\nTRIGGER: \"Pest Cleaner script stopped. [Finished]\"\n  COMMAND: /warp garden\n  HOLD: shift for 350ms\n  COMMAND: .ez-startscript netherwart:1\n");
                    appendVisitorTriggers(t);
                    appendServerShutdownTrigger(t);
                }
                default -> {
                    t.append("# none config\n");
                    if (eqSwapEnabled) { t.append("EQPestCD:\n  COMMAND: .ez-stopscript\n  EQSWAP: PEST\n  COMMAND: .ez-startscript netherwart:1 after 50ms\n\n"); }
                    t.append("TRIGGER: \"spawned in\"\n  COMMAND: .ez-stopscript\n");
                    if (eqSwapEnabled) t.append("  EQSWAP: BLOSSOM/LOTUS\n");
                    t.append("  COMMAND: /setspawn\n");
                    if (etherwarpEnabled) t.append(buildEtherwarpLine());
                    t.append("  COMMAND: .ez-startscript misc:pestCleaner\n\nTRIGGER: \"Pest Cleaner script stopped. [Finished]\"\n  COMMAND: /warp garden\n  HOLD: shift for 350ms\n  COMMAND: .ez-startscript netherwart:1\n");
                    appendVisitorTriggers(t);
                    appendServerShutdownTrigger(t);
                }
            }
            String existing = Files.exists(configPath) ? stripPestTriggerBlocks(Files.readString(configPath)) : "";
            Files.writeString(configPath, existing.stripTrailing() + "\n\n" + t);
            loadTriggers();
        } catch (IOException e) { LOGGER.error("writePestTriggers failed", e); }
    }

    private static String stripPestTriggerBlocks(String content) {
        String[] lines = content.split("\n");
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            if (line.equals("# rodswap config") || line.equals("# wardrobe swap config") || line.equals("# none config")) { i++; continue; }
            if (line.equals("# visitor support (dont change unless buggy)")) {
                i++;
                while (i < lines.length) {
                    String l = lines[i].trim();
                    if (l.startsWith("TRIGGER:") && (l.contains("script stopped. [Visitors]") || l.contains("Visitor script stopped. [Finished]"))) {
                        i++;
                        while (i < lines.length) { String inner = lines[i].trim(); if (inner.startsWith("TRIGGER:") || inner.startsWith("PestCD:") || inner.matches("PestAlive\\d+:.*") || inner.startsWith("#")) break; i++; }
                    } else break;
                }
                continue;
            }
            boolean isPestBlock = line.startsWith("PestCD:") || line.startsWith("EQPestCD:") || line.startsWith("EQPestCDWD:") || line.startsWith("EQPestCDFIN:") || line.matches("PestAlive\\d+:.*")
                || (line.startsWith("TRIGGER:") && (line.contains("Pest Cleaner script stopped") || line.contains("Wardrobe Swap script stopped") || line.contains("spawned in") || line.contains("server will restart soon") || line.contains("proxy is")))
                || line.equals("# server shutdown safety (dont change unless buggy)");
            if (isPestBlock) {
                i++;
                while (i < lines.length) { String l = lines[i].trim(); if (l.startsWith("TRIGGER:") || l.startsWith("PestCD:") || l.startsWith("EQPestCD:") || l.startsWith("EQPestCDWD:") || l.startsWith("EQPestCDFIN:") || l.matches("PestAlive\\d+:.*") || l.startsWith("#")) break; i++; }
            } else { out.append(lines[i]).append("\n"); i++; }
        }
        return out.toString();
    }

    private static void finishSetup() {
        setupWizardActive = false;
        chatTriggersEnabled = true; coordTriggersEnabled = true;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        try {
            StringBuilder config = new StringBuilder();
            config.append("# ==========================================\n# TAUN+++ AUTO-GENERATED CONFIG\n# ==========================================\n\n");
            config.append("# @ROD_SLOT = ").append(setupData.get("rod_slot")).append("\n");
            config.append("# @FARMING_TOOL_SLOT = ").append(setupData.get("farming_slot")).append("\n");
            config.append("@GARDEN = /warp garden\n\n# ===== TRIGGERS =====\n");
            Files.writeString(configPath, config.toString());
            String swapMode = setupData.getOrDefault("swap_mode", "none");
            writePestTriggers(swapMode);
            rodswapEnabled = swapMode.equals("rodswap");
            wardrobeSwapEnabled = swapMode.equals("wdswap");
            saveSettings(); loadTriggers();
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §a§l Setup Complete! Loaded §a" + triggers.size() + " §7triggers"), false);
        } catch (IOException e) { client.player.sendMessage(Text.literal("§c§lTaun+++ >> §cFailed to save config: " + e.getMessage()), false); }
    }

    private static void cancelSetup() {
        setupWizardActive = false; chatTriggersEnabled = true; coordTriggersEnabled = true; setupStep = 0; setupData.clear();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) client.player.sendMessage(Text.literal("§c§lTaun+++ >> §cSetup cancelled"), false);
    }

    public static boolean isSetupActive() { return setupWizardActive; }

    public static void addSetspawnCoords() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        double x = client.player.getX(), y = client.player.getY(), z = client.player.getZ();
        try {
            List<String> lines = Files.exists(coordConfigPath) ? new ArrayList<>(Files.readAllLines(coordConfigPath)) : new ArrayList<>();
            String line = String.format(Locale.US, "%.2f,%.2f,%.2f,1.00 = .ez-startscript netherwart:1", x, y, z);
            lines.add(line);
            Files.write(coordConfigPath, lines); loadCoordinateTriggers();
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §a✓ Added setspawn at " + String.format("%.1f, %.1f, %.1f", x, y, z)), false);
            if (setupWizardActive && setupStep == 4) { setupStep++; showSetupStep(); }
        } catch (IOException e) { LOGGER.error("Failed to save setspawn", e); }
    }

    public static void addSetendCoords() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        double x = client.player.getX(), y = client.player.getY(), z = client.player.getZ();
        String plotNum = setupData.get("plot_number");
        if (plotNum == null) { if (client.player != null) client.player.sendMessage(Text.literal("§c§lTaun+++ >> §c✗ Run /pest setup first to set plot number"), false); return; }
        try {
            List<String> lines = Files.exists(coordConfigPath) ? new ArrayList<>(Files.readAllLines(coordConfigPath)) : new ArrayList<>();
            lines.add(String.format(Locale.US, "%.2f,%.2f,%.2f,1.00,1000 = /plottp " + plotNum, x, y, z));
            lines.add(String.format(Locale.US, "%.2f,%.2f,%.2f,1.00 = .ez-stopscript", x, y, z));
            Files.write(coordConfigPath, lines); loadCoordinateTriggers();
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §a✓ Added setend at " + String.format("%.1f, %.1f, %.1f", x, y, z)), false);
            if (setupWizardActive && setupStep == 5) { setupStep++; showSetupStep(); }
        } catch (IOException e) { LOGGER.error("Failed to save setend", e); }
    }

    public static void addSetSpawnTriggerCoords() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        double x = client.player.getX(), y = client.player.getY(), z = client.player.getZ();
        try {
            List<String> lines = Files.exists(coordConfigPath) ? new ArrayList<>(Files.readAllLines(coordConfigPath)) : new ArrayList<>();
            lines.add(String.format(Locale.US, "%.2f,%.2f,%.2f,1.00 = /setspawn", x, y, z));
            Files.write(coordConfigPath, lines); loadCoordinateTriggers();
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §a✓ Added spawn trigger at " + String.format("%.1f, %.1f, %.1f", x, y, z)), false);
            if (setupWizardActive && setupStep == 6) finishSetup();
        } catch (IOException e) { LOGGER.error("Failed to save spawn trigger", e); }
    }

    public static void randomizeDelays(String input) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        // Parse variance: "25ms" -> 25, "1s" -> 1000, "25" (bare) -> 25ms
        input = input.trim().toLowerCase();
        long varianceMs;
        try {
            if (input.endsWith("ms")) varianceMs = Long.parseLong(input.substring(0, input.length() - 2).trim());
            else if (input.endsWith("s")) varianceMs = Long.parseLong(input.substring(0, input.length() - 1).trim()) * 1000;
            else varianceMs = Long.parseLong(input); // bare number = ms
        } catch (NumberFormatException e) {
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §cInvalid variance. Try: §e25ms§c, §e1s§c, §e50"), false);
            return;
        }
        if (varianceMs < 1) { client.player.sendMessage(Text.literal("§c§lTaun+++ >> §cVariance must be >= 1ms."), false); return; }
        final long fVarianceMs = varianceMs;
        try {
            if (!Files.exists(configPath)) { client.player.sendMessage(Text.literal("§c§lTaun+++ >> §ctriggers.txt not found."), false); return; }
            String content = Files.readString(configPath);
            java.util.Random rng = new java.util.Random();
            // Match: after/for <N>ms | after/for <N>s | after/for <N> (bare = ms)
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\b(after|for)\\s+(\\d+)(ms|s)?\\b");
            java.util.regex.Matcher m = p.matcher(content);
            StringBuffer sb = new StringBuffer();
            int count = 0;
            while (m.find()) {
                String keyword = m.group(1);
                long value     = Long.parseLong(m.group(2));
                String unit    = m.group(3) != null ? m.group(3) : ""; // "" means bare (treat as ms)
                // Convert to ms, apply variance, convert back
                long valueMs   = unit.equals("s") ? value * 1000 : value;
                long delta     = Math.round(rng.nextGaussian() * fVarianceMs);
                delta          = Math.max(-fVarianceMs, Math.min(fVarianceMs, delta));
                long newMs     = Math.max(1, valueMs + delta);
                String replacement;
                if (unit.equals("s"))   replacement = keyword + " " + Math.max(1, newMs / 1000) + "s";
                else if (unit.equals("ms")) replacement = keyword + " " + newMs + "ms";
                else                    replacement = keyword + " " + newMs; // bare number stays bare
                m.appendReplacement(sb, replacement);
                count++;
            }
            m.appendTail(sb);
            Files.writeString(configPath, sb.toString());
            loadTriggers();
            client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7Randomized §e" + count + " §7delay(s) in triggers.txt §7(±" + fVarianceMs + "ms)"), false);
        } catch (IOException e) {
            LOGGER.error("Failed to randomize delays", e);
        }
    }

    public static void showHelp() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        client.player.sendMessage(Text.literal("§c§l========= Taun+++ Commands ========="), false);
        client.player.sendMessage(Text.literal("§e/pest setup / setspawn / setend / setspawntrigger"), false);
        client.player.sendMessage(Text.literal("§e/pest rodswap / wdswap / etherwarp / eqswap"), false);
        client.player.sendMessage(Text.literal("§e/pest toggle chat / coords / all"), false);
        client.player.sendMessage(Text.literal("§e/pest dynarest §8— toggle on/off"), false);
        client.player.sendMessage(Text.literal("§e/pest dynarest <time> §8— set farm time (e.g. 2h, 90m)"), false);
        client.player.sendMessage(Text.literal("§e/pest dynarest breaktime <m> / scriptoffset <m> / status"), false);
        client.player.sendMessage(Text.literal("§e/pest abiphoneslot <1-9>   §7Set Abiphone hotbar slot (George sell)"), false);
        client.player.sendMessage(Text.literal("§e/pest georgesell   §7Toggle George slug auto-sell on/off"), false);
        client.player.sendMessage(Text.literal("§e/pest extrasell   §7Toggle overclocker/extra item sell via booster cookie GUI"), false);
        client.player.sendMessage(Text.literal("§e/pest random <ms> §8— add ±<ms> runtime variance to all delays (0 to disable)"), false);
        client.player.sendMessage(Text.literal("§e/pest reload / detect / files / debug / help"), false);
        client.player.sendMessage(Text.literal("§c§l====================================="), false);
    }

    public static void openConfigFolder() {
        try {
            Path folder = configPath.getParent(); Files.createDirectories(folder);
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) Runtime.getRuntime().exec("explorer " + folder.toAbsolutePath());
            else if (os.contains("mac")) Runtime.getRuntime().exec("open " + folder.toAbsolutePath());
            else Runtime.getRuntime().exec("xdg-open " + folder.toAbsolutePath());
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) client.player.sendMessage(Text.literal("§c§lTaun+++ >> §aOpening config folder..."), false);
        } catch (IOException e) { LOGGER.error("Failed to open config folder", e); }
    }

    public static int findRodSlotInHotbar() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            var stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty() && net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString().equals("minecraft:fishing_rod")) return i + 1;
        }
        return -1;
    }

    public static int findFarmingToolSlotInHotbar() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            var stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty()) { String n = stack.getName().getString().toLowerCase(); if (n.contains("mk.") || n.contains("mk ")) return i + 1; }
        }
        return -1;
    }

    public static int findAotvSlotInHotbar() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            var stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty()) { String n = stack.getName().getString().toLowerCase(); if (n.contains("aspect of the void") || n.contains("aspect of the end")) return i + 1; }
        }
        return -1;
    }

    public static Map<String, Integer> detectHotbarTools() {
        MinecraftClient client = MinecraftClient.getInstance();
        Map<String, Integer> tools = new HashMap<>();
        if (client.player == null) return tools;
        for (int i = 0; i < 9; i++) {
            var stack = client.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            String name = stack.getName().getString().toLowerCase();
            String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString();
            if (name.contains("mk.") || name.contains("mk ")) tools.put("farming_tool", i + 1);
            if (name.contains("aspect of the void") || name.contains("aspect of the end")) tools.put("aotv", i + 1);
            if (id.equals("minecraft:fishing_rod")) tools.put("rod", i + 1);
            if (name.contains("abiphone")) tools.put("abiphone", i + 1);
        }
        return tools;
    }

    public static void showDetectedTools() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        Map<String, Integer> tools = detectHotbarTools();
        client.player.sendMessage(Text.literal("§6§l===== Hotbar Tool Detection ====="), false);
        client.player.sendMessage(Text.literal(tools.containsKey("farming_tool") ? "§c§lTaun+++ >> §a✓ Farming Tool: §7Slot " + tools.get("farming_tool") : "§c§lTaun+++ >> §c✗ Farming Tool: §7Not found"), false);
        client.player.sendMessage(Text.literal(tools.containsKey("aotv") ? "§c§lTaun+++ >> §a✓ AOTV: §7Slot " + tools.get("aotv") : "§c§lTaun+++ >> §c✗ AOTV: §7Not found"), false);
        client.player.sendMessage(Text.literal(tools.containsKey("rod") ? "§c§lTaun+++ >> §a✓ Rod: §7Slot " + tools.get("rod") : "§c§lTaun+++ >> §c✗ Rod: §7Not found"), false);
        client.player.sendMessage(Text.literal(tools.containsKey("abiphone") ? "§c§lTaun+++ >> §a✓ Abiphone: §7Slot " + tools.get("abiphone") + (abiphoneSlot > 0 ? " §7(pinned to slot " + abiphoneSlot + ")" : " §7(auto-detected)") : "§c§lTaun+++ >> §c✗ Abiphone: §7Not found"), false);
        client.player.sendMessage(Text.literal("§c§lTaun+++ >> §7§o@ROD_SLOT and @FARMING_TOOL_SLOT are resolved dynamically."), false);
    }
}
