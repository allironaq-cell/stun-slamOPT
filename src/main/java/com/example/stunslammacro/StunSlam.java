package com.example.stunslammacro;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;

/**
 * The stun slam logic.
 *
 * You left-click a player (from ANY hotbar slot):
 *   - shield UP    -> hotbar goes to the axe + axe hit, then {@code gapTicks} (2) ticks later
 *                     to the mace + mace hit
 *   - shield DOWN  -> hotbar goes to the mace + mace hit straight away
 * Afterwards your original slot is restored.
 *
 * If axe/mace are missing from the hotbar (or the target isn't a player) the macro stays out of
 * the way and your normal attack happens untouched.
 */
public final class StunSlam {

    private enum Phase { IDLE, AXE, MACE, RESTORE }

    private final StunSlamConfig config;

    private Phase phase = Phase.IDLE;
    private boolean internalAttack = false; // true while WE call attackEntity (so we don't intercept ourselves)
    private PlayerEntity target;
    private int axeSlot = -1;
    private int maceSlot = -1;
    private int originalSlot = 0;
    private int ticksLeft = 0;
    private int retries = 0;

    public StunSlam(StunSlamConfig config) {
        this.config = config;
    }

    /**
     * Called from the attack callback.
     * @return true if the macro takes over this click (vanilla attack is cancelled), false to let it through.
     */
    public boolean onAttack(MinecraftClient client, Entity entity) {
        if (internalAttack) return false;                              // our own attack, let it through
        if (phase == Phase.AXE || phase == Phase.MACE) return true;    // sequence running: swallow extra clicks

        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null || client.getNetworkHandler() == null) return false;
        if (!(entity instanceof PlayerEntity victim) || victim == player || !victim.isAlive()) return false;

        // Optional: only run while falling (off by default so it behaves the same every time).
        if (config.requireFalling && (player.isOnGround() || player.fallDistance <= config.minFallDistance)) {
            return false;
        }

        PlayerInventory inv = player.getInventory();
        int mace = findMace(inv);
        if (mace < 0) return false;                // no mace in hotbar -> normal hit

        boolean shieldUp = isShieldUp(victim);
        int axe = -1;
        int current = inv.getSelectedSlot();
        if (shieldUp) {
            axe = findAxe(inv);
            if (axe < 0) return false;             // no axe in hotbar -> normal hit
        } else if (current == mace) {
            return false;                          // already holding the mace, no shield -> plain vanilla hit
        }

        // If a previous sequence is still waiting to restore the slot, keep ITS home slot.
        int home = (phase == Phase.RESTORE) ? originalSlot : current;

        this.target = victim;
        this.maceSlot = mace;
        this.axeSlot = axe;
        this.originalSlot = home;
        this.ticksLeft = 0;                        // first step runs this very tick
        this.retries = 0;
        this.phase = shieldUp ? Phase.AXE : Phase.MACE;
        return true;
    }

    /** Runs every client tick. */
    public void tick(MinecraftClient client) {
        if (phase == Phase.IDLE) return;

        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null || client.getNetworkHandler() == null) {
            reset();
            return;
        }

        if (ticksLeft > 0) ticksLeft--;
        if (ticksLeft > 0) return;

        // Waiting period after the mace hit is over: put the hotbar back.
        if (phase == Phase.RESTORE) {
            restoreSlot(player);
            reset();
            return;
        }

        if (target == null || !target.isAlive() || target.isRemoved()) {
            finish(client);
            return;
        }

        // Target slightly out of reach right now? Give it a couple of ticks before giving up.
        if (!player.canInteractWithEntity(target, 0.0)) {
            if (retries++ < config.rangeRetryTicks) {
                ticksLeft = 1;
                return;
            }
            finish(client);
            return;
        }
        retries = 0;

        if (phase == Phase.AXE) {
            strike(client, axeSlot);
            phase = Phase.MACE;
            ticksLeft = Math.max(1, config.gapTicks); // mace lands gapTicks later
        } else { // MACE
            strike(client, maceSlot);
            if (!config.silentSwap && config.restoreDelayTicks > 0) {
                // Keep the mace visibly selected for a moment, then go back.
                phase = Phase.RESTORE;
                ticksLeft = config.restoreDelayTicks;
                target = null;
            } else {
                finish(client);
            }
        }
    }

    /** Select a hotbar slot and attack the target with it. */
    private void strike(MinecraftClient client, int slot) {
        ClientPlayerEntity player = client.player;
        if (config.silentSwap) {
            // Tell the server about the slot + attack; the local hotbar stays where it is.
            client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
            client.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(target, player.isSneaking()));
        } else {
            // Normal visible switch: the hotbar highlight really moves to the axe / mace.
            player.getInventory().setSelectedSlot(slot);
            internalAttack = true;
            try {
                client.interactionManager.attackEntity(player, target);
            } finally {
                internalAttack = false;
            }
        }
        player.swingHand(Hand.MAIN_HAND);
    }

    /** End of sequence (or abort): put the hotbar back right away. */
    private void finish(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player != null && client.getNetworkHandler() != null) {
            if (config.silentSwap) {
                client.getNetworkHandler().sendPacket(
                        new UpdateSelectedSlotC2SPacket(player.getInventory().getSelectedSlot()));
            } else {
                restoreSlot(player);
            }
        }
        reset();
    }

    /** Go back to the original slot, unless the player already moved to some other slot themselves. */
    private void restoreSlot(ClientPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        int now = inv.getSelectedSlot();
        if (now == axeSlot || now == maceSlot) {
            inv.setSelectedSlot(originalSlot);
        }
    }

    private void reset() {
        phase = Phase.IDLE;
        target = null;
        retries = 0;
        ticksLeft = 0;
    }

    // ------------------------------------------------------------ helpers

    /** Shield counts as "up" even in the first few ticks after it's raised. */
    private static boolean isShieldUp(PlayerEntity victim) {
        if (victim.isBlocking()) return true;
        return victim.isUsingItem() && victim.getActiveItem().isOf(Items.SHIELD);
    }

    private static int findMace(PlayerInventory inv) {
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.MACE)) return i;
        }
        return -1;
    }

    private static int findAxe(PlayerInventory inv) {
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isIn(ItemTags.AXES)) return i;
        }
        return -1;
    }
}
