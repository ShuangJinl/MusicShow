package com.lyricsync.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public final class LyricRenderSettings {
    public static final LyricRenderSettings INSTANCE = new LyricRenderSettings();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("lyricsync-client.json");

    public boolean boldText = false;
    public boolean textShadow = true;
    public boolean randomColor = true;
    public boolean flashyText = true;
    public int fixedColorRgb = 0xFFFFFF;
    public boolean transparentBackground = true;
    public boolean followPlayerView = false;
    public boolean fixedModeFacePlayer = true;

    public float minDistance = 4.0f;
    public float maxDistance = 8.0f;
    public float yOffset = 0.35f;
    public float particleYOffset = -0.5f;
    public int particleCount = 2;
    public String particleType = "END_ROD";

    private LyricRenderSettings() {
    }

    public void resetDefaults() {
        boldText = false;
        textShadow = true;
        randomColor = true;
        flashyText = true;
        fixedColorRgb = 0xFFFFFF;
        transparentBackground = true;
        followPlayerView = false;
        fixedModeFacePlayer = true;
        minDistance = 4.0f;
        maxDistance = 8.0f;
        yOffset = 0.35f;
        particleYOffset = -0.5f;
        particleCount = 2;
        particleType = "END_ROD";
    }

    public void load() {
        try {
            if (!Files.exists(CONFIG_PATH)) {
                return;
            }
            String json = Files.readString(CONFIG_PATH);
            LyricRenderSettings loaded = GSON.fromJson(json, LyricRenderSettings.class);
            if (loaded == null) {
                return;
            }
            boldText = loaded.boldText;
            textShadow = loaded.textShadow;
            randomColor = loaded.randomColor;
            flashyText = loaded.flashyText;
            fixedColorRgb = loaded.fixedColorRgb;
            transparentBackground = loaded.transparentBackground;
            followPlayerView = loaded.followPlayerView;
            fixedModeFacePlayer = loaded.fixedModeFacePlayer;
            minDistance = loaded.minDistance;
            maxDistance = loaded.maxDistance;
            yOffset = loaded.yOffset;
            particleYOffset = loaded.particleYOffset;
            particleCount = loaded.particleCount;
            particleType = loaded.particleType;
            sanitize();
        } catch (Exception ignored) {
            // Keep defaults if config is invalid.
        }
    }

    public void save() {
        sanitize();
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(this));
        } catch (Exception ignored) {
            // Non-fatal: runtime continues even if saving fails.
        }
    }

    private void sanitize() {
        minDistance = Math.max(1.0f, minDistance);
        maxDistance = Math.max(minDistance, maxDistance);
        particleCount = Math.max(0, Math.min(24, particleCount));
        if (particleType == null || particleType.isBlank()) {
            particleType = "END_ROD";
        }
    }
}
