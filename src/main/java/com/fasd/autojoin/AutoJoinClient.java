package com.fasd.autojoin;

import com.fasd.autojoin.mixin.HandledScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loop: "Map Selection" -> click target map -> if "currently full" -> "Back" -> repeat.
 * Usage: open Server Selection, HOVER the map you want, press J. Press J again to stop.
 */
public class AutoJoinClient implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("AutoJoin");

    // ================= SETTINGS =================
    static final int TOGGLE_KEY = GLFW.GLFW_KEY_J;
    /** If nothing happened after a click, click again after this many ticks (20 ticks = 1s). */
    static final int RETRY_TICKS = 8;
    /** No container open for this many ticks while running => assume you joined, stop. */
    static final int NO_SCREEN_STOP_TICKS = 20;
    static final String MAP_SELECTION_NAME = "map selection";
    static final String BACK_NAME = "back";
    static final String FULL_TEXT = "currently full";
    /** Seen in the clip: a free spot => "Queueing for Specific Game", a "Leave Queue" hotbar item, menu stays open. */
    static final String LEAVE_QUEUE_NAME = "leave queue";
    static final String QUEUE_TEXT = "queueing for";
    static final String GAME_FOUND_TEXT = "game found";
    static final int QUEUE_MSG_HOLD_TICKS = 60;
    /**
     * After clicking the map, if no queue signal shows up within this many ticks, assume it was full and click Back.
     * This works even if the "currently full" chat message is never detected. Raise it if you have high ping.
     */
    static final int FULL_TIMEOUT_TICKS = 4;
    /** After clicking Back / Map Selection, wait for the server to open the next menu (takes ~0.5s) before trying again. */
    static final int NAV_RETRY_TICKS = 30;
    /** Print every action the mod takes in chat so you can see what it's doing. */
    static final boolean DEBUG = true;
    // ============================================

    private static boolean running;
    private static boolean keyWasDown;
    private static boolean fullFlag;
    private static String targetName;
    private static int targetIndex = -1;
    private static ScreenHandler lastHandler;
    private static int tick, lastClickTick, noScreenTicks;
    private static int lastQueueMsgTick = -10000;
    private static boolean announcedQueue;
    private static boolean awaiting;
    private static boolean pendingNav;
    private static int targetClickTick;
    private static ScreenHandler dumpedHandler;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(AutoJoinClient::onTick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!running) return;
            String text = message.getString().toLowerCase();
            LOG.info("msg overlay={} text={}", overlay, message.getString());
            if (text.contains(QUEUE_TEXT) || text.contains(GAME_FOUND_TEXT)) {
                lastQueueMsgTick = tick;                 // we got a spot: stop clicking
            } else if (text.contains(FULL_TEXT)) {
                fullFlag = true;
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> act(mc));               // react immediately, don't wait for next tick
            }
        });
    }

    private static void onTick(MinecraftClient mc) {
        tick++;
        if (mc.player == null) {
            if (running) { running = false; }
            return;
        }
        pollKey(mc);
        if (running) act(mc);
    }

    private static void pollKey(MinecraftClient mc) {
        // only react in-game or inside a chest-style menu (never while typing in chat/anvil/etc.)
        if (mc.currentScreen != null && !(mc.currentScreen instanceof GenericContainerScreen)) return;
        boolean down = GLFW.glfwGetKey(mc.getWindow().getHandle(), TOGGLE_KEY) == GLFW.GLFW_PRESS;
        if (down && !keyWasDown) toggle(mc);
        keyWasDown = down;
    }

    private static void toggle(MinecraftClient mc) {
        if (running) { stop(mc, "Stopped."); return; }

        if (mc.currentScreen instanceof GenericContainerScreen gcs) {
            Slot hovered = ((HandledScreenAccessor) gcs).autojoin$getFocusedSlot();
            if (hovered != null && hovered.hasStack() && hovered.inventory != mc.player.getInventory()) {
                String n = name(hovered.getStack());
                if (!n.equalsIgnoreCase(BACK_NAME) && !n.equalsIgnoreCase(MAP_SELECTION_NAME)) {
                    targetName = n;
                    targetIndex = hovered.id;
                }
            }
        }
        if (targetName == null) {
            msg(mc, "Open Server Selection, hover the map you want, then press J.", Formatting.RED);
            return;
        }
        running = true;
        fullFlag = false;
        lastHandler = null;
        noScreenTicks = 0;
        lastClickTick = tick - RETRY_TICKS;
        lastQueueMsgTick = -10000;
        announcedQueue = false;
        awaiting = false;
        pendingNav = false;
        msg(mc, "Spamming \"" + targetName + "\" - press J to stop.", Formatting.GREEN);
        act(mc);
    }

    private static void act(MinecraftClient mc) {
        if (!running || mc.player == null || mc.interactionManager == null) return;

        // In the queue (got a spot): do NOT click anything, just wait for the game to start.
        if (inQueue(mc)) {
            noScreenTicks = 0;
            fullFlag = false;
            awaiting = false;
            if (!announcedQueue) {
                announcedQueue = true;
                msg(mc, "Got a spot - queued! Waiting for the game to start (J to stop).", Formatting.GREEN);
            }
            return;
        }
        announcedQueue = false;

        if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) {
            if (++noScreenTicks >= NO_SCREEN_STOP_TICKS) stop(mc, "Menu closed - you probably got in. Stopped.");
            return;
        }
        noScreenTicks = 0;

        ScreenHandler h = gcs.getScreenHandler();
        // Just clicked Back / Map Selection and the server hasn't opened the next menu yet: do NOT click anything in the stale menu.
        if (pendingNav && h == lastHandler && tick - lastClickTick < NAV_RETRY_TICKS) return;
        if (h != lastHandler) pendingNav = false;
        boolean newMenu = h != lastHandler;
        boolean retry = tick - lastClickTick >= RETRY_TICKS;

        Slot mapSel = findByName(mc, h, MAP_SELECTION_NAME);
        if (mapSel != null) {                       // Free For All menu
            fullFlag = false;
            awaiting = false;
            if (newMenu || retry) click(mc, h, mapSel, "Map Selection");
            return;
        }

        Slot back = findByName(mc, h, BACK_NAME);
        if (back != null) {                         // Server Selection menu
            if (awaiting) {
                // We clicked the map. Full message OR no queue signal after a few ticks => it was full => Back.
                if (fullFlag || tick - targetClickTick >= FULL_TIMEOUT_TICKS) {
                    boolean fullFlagWas = fullFlag;
                    fullFlag = false;
                    awaiting = false;
                    click(mc, h, back, fullFlagWas ? "Back (full msg)" : "Back (timeout)");
                }
                return;
            }
            fullFlag = false;
            if (newMenu || retry) {
                Slot target = findTarget(mc, h);
                if (target != null) {
                    click(mc, h, target, "map " + targetName);
                    awaiting = true;
                    targetClickTick = tick;
                }
            }
            return;
        }

        // Neither "Map Selection" nor "Back" found: log slot names once so we can see what this menu really contains.
        if (dumpedHandler != h) {
            dumpedHandler = h;
            StringBuilder sb = new StringBuilder("Unknown menu '" + gcs.getTitle().getString() + "' slots: ");
            for (Slot sl : h.slots) {
                if (sl.inventory != mc.player.getInventory() && sl.hasStack()) {
                    sb.append('[').append(sl.id).append(':').append(name(sl.getStack())).append("] ");
                }
            }
            LOG.info(sb.toString());
        }
    }

    private static boolean inQueue(MinecraftClient mc) {
        if (tick - lastQueueMsgTick < QUEUE_MSG_HOLD_TICKS) return true;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            if (!st.isEmpty() && name(st).equalsIgnoreCase(LEAVE_QUEUE_NAME)) return true;
        }
        return false;
    }

    private static void click(MinecraftClient mc, ScreenHandler h, Slot slot, String label) {
        mc.interactionManager.clickSlot(h.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
        lastHandler = h;
        lastClickTick = tick;
        pendingNav = !label.startsWith("map ");
        LOG.info("click {} (slot {}, tick {})", label, slot.id, tick);
        if (DEBUG) msg(mc, "click: " + label, Formatting.GRAY);
    }

    private static Slot findTarget(MinecraftClient mc, ScreenHandler h) {
        if (targetIndex >= 0 && targetIndex < h.slots.size()) {
            Slot s = h.slots.get(targetIndex);
            if (s.hasStack() && name(s.getStack()).equalsIgnoreCase(targetName)) return s;
        }
        return findByName(mc, h, targetName);
    }

    private static Slot findByName(MinecraftClient mc, ScreenHandler h, String wanted) {
        for (Slot s : h.slots) {
            if (s.inventory == mc.player.getInventory() || !s.hasStack()) continue;
            if (name(s.getStack()).equalsIgnoreCase(wanted)) return s;
        }
        return null;
    }

    private static String name(ItemStack stack) {
        return stack.getName().getString().trim();
    }

    private static void stop(MinecraftClient mc, String why) {
        running = false;
        msg(mc, why, Formatting.YELLOW);
    }

    private static void msg(MinecraftClient mc, String text, Formatting color) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[AutoJoin] " + text).formatted(color), false);
    }
}
