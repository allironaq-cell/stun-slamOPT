package com.example.stunslammacro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Config file: .minecraft/config/stunslammacro.json (edit while the game is closed). */
public final class StunSlamConfig {

    private static final int CURRENT_VERSION = 2;

    /** Used to reset old config files when defaults change. Don't edit. */
    public int configVersion = 0;

    /** Ticks between the axe hit and the mace hit. */
    public int gapTicks = 2;

    /**
     * false = you SEE the hotbar switch: current slot -> axe -> mace -> back (default).
     * true  = slot changes are sent to the server only; your screen stays on your slot.
     */
    public boolean silentSwap = false;

    /** How many ticks the mace slot stays selected after the last hit before going back (visible mode). */
    public int restoreDelayTicks = 3;

    /** If true, the macro only runs while falling. false = it always runs (most consistent). */
    public boolean requireFalling = false;

    /** Used only when requireFalling is true. */
    public double minFallDistance = 1.5;

    /** If the target is briefly out of reach when the next hit is due, retry for this many ticks. */
    public int rangeRetryTicks = 2;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("stunslammacro.json");
    }

    public static StunSlamConfig load() {
        Path file = path();
        StunSlamConfig config = new StunSlamConfig();
        try {
            if (Files.exists(file)) {
                StunSlamConfig read = GSON.fromJson(Files.readString(file), StunSlamConfig.class);
                // Old files (from the first version) are replaced by the new defaults.
                if (read != null && read.configVersion >= CURRENT_VERSION) config = read;
            }
            config.configVersion = CURRENT_VERSION;
            config.gapTicks = Math.max(1, config.gapTicks);
            config.restoreDelayTicks = Math.max(0, config.restoreDelayTicks);
            config.rangeRetryTicks = Math.max(0, config.rangeRetryTicks);
            Files.writeString(file, GSON.toJson(config));
        } catch (IOException | RuntimeException e) {
            System.err.println("[StunSlam] Could not read/write config, using defaults: " + e);
        }
        return config;
    }
}
