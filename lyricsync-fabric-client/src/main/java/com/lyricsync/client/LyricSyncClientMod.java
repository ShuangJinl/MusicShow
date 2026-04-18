package com.lyricsync.client;

import com.lyricsync.client.config.LyricRenderSettings;
import com.lyricsync.client.model.LyricUpdateMessage;
import com.lyricsync.client.mixin.BlockDisplayEntityAccessor;
import com.lyricsync.client.mixin.TextDisplayEntityAccessor;
import com.lyricsync.client.net.LyricWebSocketClient;
import com.lyricsync.client.queue.LyricEventQueue;
import com.lyricsync.client.ui.LyricSettingsScreen;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.util.InputUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class LyricSyncClientMod implements ClientModInitializer {
    public static final String MOD_ID = "lyricsync";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String WS_URL = "ws://localhost:8765";
    private static final String SIDECAR_HOST = "127.0.0.1";
    private static final int SIDECAR_PORT = 8765;
    private static final int MAX_MESSAGES_PER_TICK = 20;
    private static final int POSITION_SAMPLE_ATTEMPTS = 16;
    private static final long FADE_IN_MS = 250L;
    private static final long FADE_OUT_MS = 500L;
    private static final long EARLY_FADE_OUT_ON_NEXT_MS = 400L;
    private static final int AMBIENT_PARTICLE_INTERVAL_TICKS = 6;
    private static final int EXIT_PARTICLE_BURST = 2;
    private static final int LIGHT_BLOCK_LEVEL = 9;

    private LyricWebSocketClient wsClient;
    private ActiveLyricDisplay activeDisplay;
    private final List<FadingLyricDisplay> fadingDisplays = new ArrayList<>();
    private long tickCounter;
    private long lyricStartAtMs;
    private long lyricEndAtMs;
    private KeyBinding openSettingsKey;
    private final LyricRenderSettings settings = LyricRenderSettings.INSTANCE;
    private Process sidecarProcess;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[LyricSync] Initializing client.");
        settings.load();
        ensureSidecarRunning();
        wsClient = new LyricWebSocketClient(URI.create(WS_URL), LOGGER);
        wsClient.startClient();
        openSettingsKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                "key.lyricsync.open_settings",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                KeyBinding.Category.create(Identifier.of(MOD_ID, "general"))
            )
        );
        registerClientCommands();

        ClientTickEvents.END_CLIENT_TICK.register(client -> consumeQueueOnMainThread());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (wsClient != null) {
                wsClient.stopClient();
            }
            stopSidecar();
            destroyActiveDisplay();
        });
    }

    private void consumeQueueOnMainThread() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            clearAllDisplays();
            return;
        }
        while (openSettingsKey.wasPressed()) {
            client.setScreen(new LyricSettingsScreen(client.currentScreen));
        }

        int consumed = 0;
        while (consumed < MAX_MESSAGES_PER_TICK) {
            LyricUpdateMessage message = LyricEventQueue.poll();
            if (message == null) {
                break;
            }
            consumed++;
            showLyricText(client, message);
        }

        tickCounter++;
        updateDisplayPerTick(client);
        updateFadingDisplays(client);
    }

    private void showLyricText(MinecraftClient client, LyricUpdateMessage message) {
        long now = System.currentTimeMillis();
        if (activeDisplay != null) {
            beginFadeOut(activeDisplay, now, EARLY_FADE_OUT_ON_NEXT_MS);
            activeDisplay = null;
        }

        DisplayEntity.TextDisplayEntity textDisplay = new DisplayEntity.TextDisplayEntity(
            EntityType.TEXT_DISPLAY,
            client.world
        );

        applyTextDisplayStyle(textDisplay, message.text());
        textDisplay.setNoGravity(true);

        Vec3d position = calculateDisplayPosition(client);
        textDisplay.setPosition(position.x, position.y, position.z);
        // Always face the player once at spawn.
        rotateDisplayToFacePlayer(textDisplay, client, position);
        client.world.addEntity(textDisplay);
        DisplayEntity.BlockDisplayEntity lightDisplay = createLightDisplay(client, position);

        activeDisplay = new ActiveLyricDisplay(textDisplay, lightDisplay, position);
        lyricStartAtMs = now;
        lyricEndAtMs = lyricStartAtMs + Math.max(message.duration(), 600);
        setDisplayOpacity(textDisplay, 0);

        LOGGER.info(
            "[LyricSync] Render lyric text='{}' duration={}ms translated={}",
            message.text(),
            message.duration(),
            message.isTranslated()
        );
    }

    private void updateDisplayPerTick(MinecraftClient client) {
        if (activeDisplay == null || !activeDisplay.entity().isAlive() || client.player == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now >= lyricEndAtMs) {
            beginFadeOut(activeDisplay, now, FADE_OUT_MS);
            activeDisplay = null;
            return;
        }

        Vec3d position = activeDisplay.fixedPosition();
        activeDisplay.entity().setPosition(position.x, position.y, position.z);
        if (settings.followPlayerView || settings.fixedModeFacePlayer) {
            rotateDisplayToFacePlayer(activeDisplay.entity(), client, position);
        }
        if (activeDisplay.lightEntity() != null && activeDisplay.lightEntity().isAlive()) {
            activeDisplay.lightEntity().setPosition(position.x, position.y, position.z);
        }

        long elapsed = now - lyricStartAtMs;
        long remaining = lyricEndAtMs - now;
        int alpha;
        if (elapsed < FADE_IN_MS) {
            alpha = (int) Math.min(255, (elapsed * 255f) / FADE_IN_MS);
        } else if (remaining < FADE_OUT_MS) {
            alpha = (int) Math.max(0, (remaining * 255f) / FADE_OUT_MS);
        } else {
            alpha = 255;
        }
        if (settings.flashyText && alpha >= 240) {
            // Glow-ink-like pulse while text is fully visible.
            float pulse = (float) ((Math.sin(now / 110.0) + 1.0) * 0.5);
            alpha = 188 + Math.round(pulse * 67f);
        }
        setDisplayOpacity(activeDisplay.entity(), alpha);
        updateGlowLightLevel(activeDisplay.lightEntity(), alpha);

        if (tickCounter % AMBIENT_PARTICLE_INTERVAL_TICKS == 0) {
            spawnAmbientParticles(client, position);
        }
    }

    private Vec3d calculateDisplayPosition(MinecraftClient client) {
        Vec3d eyePos = client.player.getEyePos();
        Vec3d lookVec = client.player.getRotationVec(1.0f).normalize();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        float minDistance = Math.min(settings.minDistance, settings.maxDistance);
        float maxDistance = Math.max(settings.minDistance, settings.maxDistance);

        double distanceRange = maxDistance - minDistance;
        for (int i = 0; i < POSITION_SAMPLE_ATTEMPTS; i++) {
            double distance = distanceRange <= 1.0e-6
                ? minDistance
                : random.nextDouble(minDistance, maxDistance);
            Vec3d jitter = new Vec3d(
                random.nextDouble(-0.28, 0.28),
                random.nextDouble(-0.18, 0.22),
                random.nextDouble(-0.28, 0.28)
            );
            Vec3d direction = lookVec.add(jitter).normalize();
            Vec3d candidate = eyePos.add(direction.multiply(distance)).add(0.0, settings.yOffset, 0.0);
            if (isVisibleAndClear(client, eyePos, candidate)) {
                return candidate;
            }
        }

        return eyePos.add(lookVec.multiply(MathHelper.clamp(minDistance + 0.5f, 1.0f, maxDistance))).add(0.0, settings.yOffset, 0.0);
    }

    private void rotateDisplayToFacePlayer(DisplayEntity.TextDisplayEntity display, MinecraftClient client, Vec3d position) {
        Vec3d toPlayer = client.player.getEyePos().subtract(position);
        double horizontal = Math.sqrt(toPlayer.x * toPlayer.x + toPlayer.z * toPlayer.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(toPlayer.z, toPlayer.x)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(toPlayer.y, horizontal)));
        display.setYaw(yaw);
        display.setPitch(pitch);
    }

    private boolean isVisibleAndClear(MinecraftClient client, Vec3d eyePos, Vec3d candidate) {
        RaycastContext context = new RaycastContext(
            eyePos,
            candidate,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            client.player
        );
        HitResult hit = client.world.raycast(context);
        if (hit.getType() != HitResult.Type.MISS) {
            return false;
        }

        BlockPos blockPos = BlockPos.ofFloored(candidate);
        return client.world.getBlockState(blockPos).isReplaceable();
    }

    private void setDisplayOpacity(DisplayEntity.TextDisplayEntity display, int alpha) {
        int clamped = Math.max(0, Math.min(255, alpha));
        ((TextDisplayEntityAccessor) display).lyricsync$setTextOpacity((byte) clamped);
    }

    private void beginFadeOut(ActiveLyricDisplay display, long now, long fadeOutMs) {
        if (display == null || display.entity() == null || !display.entity().isAlive()) {
            return;
        }
        fadingDisplays.add(new FadingLyricDisplay(display.entity(), display.lightEntity(), now, fadeOutMs));
    }

    private void updateFadingDisplays(MinecraftClient client) {
        if (fadingDisplays.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<FadingLyricDisplay> iterator = fadingDisplays.iterator();
        while (iterator.hasNext()) {
            FadingLyricDisplay fading = iterator.next();
            DisplayEntity.TextDisplayEntity entity = fading.entity();
            if (!entity.isAlive()) {
                if (fading.lightEntity() != null && fading.lightEntity().isAlive()) {
                    fading.lightEntity().discard();
                }
                iterator.remove();
                continue;
            }

            long elapsed = now - fading.fadeStartAtMs();
            if (elapsed >= fading.fadeDurationMs()) {
                entity.discard();
                if (fading.lightEntity() != null && fading.lightEntity().isAlive()) {
                    fading.lightEntity().discard();
                }
                iterator.remove();
                continue;
            }

            int alpha = (int) Math.max(0, 255 - (elapsed * 255f / fading.fadeDurationMs()));
            setDisplayOpacity(entity, alpha);
            entity.setPosition(entity.getX(), entity.getY() + 0.0035, entity.getZ());
            updateGlowLightLevel(fading.lightEntity(), alpha);
            Vec3d particleCenter = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
            int burstCount = Math.max(0, settings.particleCount) * EXIT_PARTICLE_BURST;
            for (int i = 0; i < burstCount; i++) {
                spawnSoftParticle(client, particleCenter, 0.006);
            }
        }
    }

    private void spawnAmbientParticles(MinecraftClient client, Vec3d center) {
        int count = Math.max(0, settings.particleCount);
        for (int i = 0; i < count; i++) {
            spawnSoftParticle(client, center, 0.0015);
        }
    }

    private void spawnSoftParticle(MinecraftClient client, Vec3d center, double velocityScale) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double px = center.x + random.nextDouble(-0.35, 0.35);
        double py = center.y + settings.particleYOffset + random.nextDouble(-0.12, 0.15);
        double pz = center.z + random.nextDouble(-0.35, 0.35);
        double vx = random.nextDouble(-velocityScale, velocityScale);
        double vy = random.nextDouble(0.0005, velocityScale * 1.4);
        double vz = random.nextDouble(-velocityScale, velocityScale);
        client.world.addParticleClient(resolveParticleType(), px, py, pz, vx, vy, vz);
    }

    private void clearAllDisplays() {
        if (activeDisplay != null && activeDisplay.entity() != null) {
            activeDisplay.entity().discard();
            if (activeDisplay.lightEntity() != null && activeDisplay.lightEntity().isAlive()) {
                activeDisplay.lightEntity().discard();
            }
            activeDisplay = null;
        }
        for (FadingLyricDisplay fading : fadingDisplays) {
            if (fading.entity() != null && fading.entity().isAlive()) {
                fading.entity().discard();
            }
            if (fading.lightEntity() != null && fading.lightEntity().isAlive()) {
                fading.lightEntity().discard();
            }
        }
        fadingDisplays.clear();
    }

    private void destroyActiveDisplay() {
        if (activeDisplay != null) {
            activeDisplay.entity().discard();
            if (activeDisplay.lightEntity() != null && activeDisplay.lightEntity().isAlive()) {
                activeDisplay.lightEntity().discard();
            }
            activeDisplay = null;
        }
    }

    private void applyTextDisplayStyle(DisplayEntity.TextDisplayEntity textDisplay, String text) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int color = settings.randomColor
            ? (0x404040 | random.nextInt(0xBFBFBF))
            : settings.fixedColorRgb;
        Text rendered = Text.literal(text).styled(style -> style
            .withBold(settings.boldText)
            .withItalic(settings.flashyText)
            .withColor(TextColor.fromRgb(color))
        );

        TextDisplayEntityAccessor accessor = (TextDisplayEntityAccessor) textDisplay;
        accessor.lyricsync$setText(rendered);
        accessor.lyricsync$setBackground(settings.transparentBackground ? 0x00000000 : 0x7F000000);
        accessor.lyricsync$setTextOpacity((byte) 0);
        byte flags = 0x02; // see-through
        if (settings.textShadow) {
            flags |= 0x01;
        }
        accessor.lyricsync$setFlags(flags);
    }

    private DisplayEntity.BlockDisplayEntity createLightDisplay(MinecraftClient client, Vec3d position) {
        DisplayEntity.BlockDisplayEntity lightDisplay = new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, client.world);
        lightDisplay.setNoGravity(true);
        lightDisplay.setPosition(position.x, position.y, position.z);
        updateGlowLightLevel(lightDisplay, 255);
        client.world.addEntity(lightDisplay);
        return lightDisplay;
    }

    private void updateGlowLightLevel(DisplayEntity.BlockDisplayEntity lightDisplay, int alpha) {
        if (lightDisplay == null || !lightDisplay.isAlive()) {
            return;
        }
        int luminance = Math.max(0, Math.min(15, Math.round((alpha / 255f) * LIGHT_BLOCK_LEVEL)));
        BlockState state = Blocks.LIGHT.getDefaultState().with(net.minecraft.state.property.Properties.LEVEL_15, luminance);
        ((BlockDisplayEntityAccessor) lightDisplay).lyricsync$setBlockState(state);
    }

    private net.minecraft.particle.ParticleEffect resolveParticleType() {
        return switch (settings.particleType) {
            case "ENCHANT" -> ParticleTypes.ENCHANT;
            case "PORTAL" -> ParticleTypes.PORTAL;
            case "SOUL_FIRE_FLAME" -> ParticleTypes.SOUL_FIRE_FLAME;
            case "WAX_ON" -> ParticleTypes.WAX_ON;
            default -> ParticleTypes.END_ROD;
        };
    }

    private void ensureSidecarRunning() {
        if (isServerAvailable()) {
            LOGGER.info("[LyricSync] Sidecar already running on {}:{}.", SIDECAR_HOST, SIDECAR_PORT);
            return;
        }

        String cmdOverride = System.getProperty("lyricsync.sidecar.command", "").trim();
        SidecarLaunch launch = cmdOverride.isEmpty()
            ? resolveDefaultSidecarLaunch()
            : new SidecarLaunch(Arrays.asList(cmdOverride.split("\\s+")), Paths.get(System.getProperty("user.dir")));
        if (launch == null) {
            LOGGER.warn("[LyricSync] No runnable sidecar found. Checked exe and python fallback paths.");
            return;
        }

        File logFile = launch.workDir().resolve("lyricsync-sidecar.log").toFile();
        try {
            ProcessBuilder builder = new ProcessBuilder(launch.command());
            builder.directory(launch.workDir().toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
            sidecarProcess = builder.start();
            LOGGER.info("[LyricSync] Started sidecar: '{}' (cwd={})", String.join(" ", launch.command()), launch.workDir());
        } catch (IOException ex) {
            LOGGER.warn("[LyricSync] Failed to start sidecar process.", ex);
        }
    }

    private SidecarLaunch resolveDefaultSidecarLaunch() {
        Path userDir = Paths.get(System.getProperty("user.dir"));
        List<Path> exeCandidates = List.of(
            userDir.resolve("lyricsync-sidecar.exe"),
            userDir.resolve("lyricsync-sidecar").resolve("lyricsync-sidecar.exe"),
            userDir.resolve("mods").resolve("lyricsync-sidecar.exe")
        );
        for (Path exe : exeCandidates) {
            if (exe.toFile().exists()) {
                return new SidecarLaunch(List.of(exe.toAbsolutePath().toString()), exe.getParent());
            }
        }

        String dirOverride = System.getProperty("lyricsync.sidecar.dir", "").trim();
        Path sidecarDir = dirOverride.isEmpty()
            ? userDir.resolve("lyricsync-python-middleware")
            : Paths.get(dirOverride);
        if (sidecarDir.toFile().exists() && sidecarDir.resolve("server.py").toFile().exists()) {
            return new SidecarLaunch(List.of("python", "server.py"), sidecarDir);
        }
        return null;
    }

    private boolean isServerAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(SIDECAR_HOST, SIDECAR_PORT), 250);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private void stopSidecar() {
        if (sidecarProcess != null && sidecarProcess.isAlive()) {
            sidecarProcess.destroy();
            sidecarProcess = null;
        }
    }

    private void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
            dispatcher.register(
                literal("lyricsync").executes(context -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    client.execute(() -> client.setScreen(new LyricSettingsScreen(client.currentScreen)));
                    return 1;
                })
            )
        );
    }

    private record ActiveLyricDisplay(
        DisplayEntity.TextDisplayEntity entity,
        DisplayEntity.BlockDisplayEntity lightEntity,
        Vec3d fixedPosition
    ) {
    }

    private record FadingLyricDisplay(
        DisplayEntity.TextDisplayEntity entity,
        DisplayEntity.BlockDisplayEntity lightEntity,
        long fadeStartAtMs,
        long fadeDurationMs
    ) {
    }

    private record SidecarLaunch(List<String> command, Path workDir) {
    }
}
