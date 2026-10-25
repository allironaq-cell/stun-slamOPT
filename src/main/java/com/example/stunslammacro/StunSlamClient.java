package com.example.stunslammacro;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Entrypoint.
 *  - G toggles the macro and flashes a tick (top-left) - no other messages.
 *  - AttackEntityCallback catches your normal left-click on a player and hands it to {@link StunSlam}.
 */
public class StunSlamClient implements ClientModInitializer {

    public static final String MOD_ID = "stunslammacro";

    private static KeyBinding toggleKey;
    private static boolean enabled = false;
    private static int indicatorTicks = 0; // how long the top-left symbol stays on screen

    @Override
    public void onInitializeClient() {
        StunSlamConfig config = StunSlamConfig.load();
        StunSlam macro = new StunSlam(config);

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.stunslammacro.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, category));

        // Your left-click on an entity. Return FAIL = we handle the attack ourselves.
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClient() || !enabled) return ActionResult.PASS;
            return macro.onAttack(MinecraftClient.getInstance(), entity) ? ActionResult.FAIL : ActionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                indicatorTicks = 30; // ~1.5 seconds
            }
            if (indicatorTicks > 0) indicatorTicks--;
            macro.tick(client);
        });

        // Small tick / cross in the top-left corner right after pressing G.
        HudElementRegistry.addLast(Identifier.of(MOD_ID, "status"), (context, tickCounter) -> {
            if (indicatorTicks <= 0) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            String symbol = enabled ? "\u2714" : "\u2718";
            int color = enabled ? 0xFF55FF55 : 0xFFFF5555;
            context.drawTextWithShadow(mc.textRenderer, symbol, 6, 6, color);
        });
    }
}
