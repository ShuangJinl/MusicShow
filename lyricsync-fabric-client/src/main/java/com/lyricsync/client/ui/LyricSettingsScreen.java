package com.lyricsync.client.ui;

import com.lyricsync.client.config.LyricRenderSettings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

public class LyricSettingsScreen extends Screen {
    private static final int[] COLOR_OPTIONS = {
        0xFFFFFF, 0xFFD54F, 0x80DEEA, 0xF48FB1, 0xA5D6A7, 0xCE93D8
    };
    private static final String[] COLOR_NAMES = {
        "White", "Gold", "Cyan", "Pink", "Green", "Purple"
    };
    private static final String[] PARTICLE_TYPES = {
        "END_ROD", "ENCHANT", "PORTAL", "SOUL_FIRE_FLAME", "WAX_ON"
    };

    private final Screen parent;
    private final LyricRenderSettings settings = LyricRenderSettings.INSTANCE;

    public LyricSettingsScreen(Screen parent) {
        super(Text.literal("LyricSync Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 155;
        int right = this.width / 2 + 5;
        int y = this.height / 6;

        addDrawableChild(CyclingButtonWidget.onOffBuilder(settings.boldText)
            .build(left, y, 150, 20, Text.literal("Bold Text"), (b, v) -> settings.boldText = v));
        addDrawableChild(CyclingButtonWidget.onOffBuilder(settings.randomColor)
            .build(right, y, 150, 20, Text.literal("Random Color"), (b, v) -> settings.randomColor = v));
        y += 24;

        addDrawableChild(CyclingButtonWidget.onOffBuilder(settings.textShadow)
            .build(left, y, 150, 20, Text.literal("Text Shadow"), (b, v) -> settings.textShadow = v));
        addDrawableChild(CyclingButtonWidget.onOffBuilder(settings.followPlayerView)
            .build(right, y, 150, 20, Text.literal("Follow Facing"), (b, v) -> settings.followPlayerView = v));
        y += 24;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(settings.flashyText)
            .build(left, y, 305, 20, Text.literal("Glow Ink Flash Style"), (b, v) -> settings.flashyText = v));
        y += 24;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(settings.transparentBackground)
            .build(left, y, 305, 20, Text.literal("Transparent Background"), (b, v) -> settings.transparentBackground = v));
        y += 24;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(settings.fixedModeFacePlayer)
            .build(left, y, 305, 20, Text.literal("Fixed Mode: Rotate To Face Player"), (b, v) -> settings.fixedModeFacePlayer = v));
        y += 24;

        int initialColorIndex = findColorIndex(settings.fixedColorRgb);
        addDrawableChild(CyclingButtonWidget.<Integer>builder(i -> Text.literal(COLOR_NAMES[i]), initialColorIndex)
            .values(0, 1, 2, 3, 4, 5)
            .build(left, y, 305, 20, Text.literal("Fixed Color"), (b, i) -> settings.fixedColorRgb = COLOR_OPTIONS[i]));
        y += 28;

        addDrawableChild(new FloatSlider(left, y, 305, 20, "Min Distance", 1.0, 10.0, settings.minDistance, value -> {
            settings.minDistance = value.floatValue();
            if (settings.maxDistance < settings.minDistance) {
                settings.maxDistance = settings.minDistance;
            }
        }));
        y += 24;
        addDrawableChild(new FloatSlider(left, y, 305, 20, "Max Distance", 1.0, 12.0, settings.maxDistance, value -> {
            settings.maxDistance = value.floatValue();
            if (settings.minDistance > settings.maxDistance) {
                settings.minDistance = settings.maxDistance;
            }
        }));
        y += 24;
        addDrawableChild(new FloatSlider(left, y, 305, 20, "Text Y Offset", -1.0, 2.0, settings.yOffset, value ->
            settings.yOffset = value.floatValue()
        ));
        y += 24;
        addDrawableChild(new FloatSlider(left, y, 305, 20, "Particle Y Offset", -2.0, 1.0, settings.particleYOffset, value ->
            settings.particleYOffset = value.floatValue()
        ));
        y += 24;
        addDrawableChild(new IntSlider(left, y, 305, 20, "Particle Count", 0, 12, settings.particleCount, value ->
            settings.particleCount = value.intValue()
        ));
        y += 24;
        int particleIndex = findParticleTypeIndex(settings.particleType);
        addDrawableChild(CyclingButtonWidget.<Integer>builder(i -> Text.literal(PARTICLE_TYPES[i]), particleIndex)
            .values(0, 1, 2, 3, 4)
            .build(left, y, 305, 20, Text.literal("Particle Type"), (b, i) -> settings.particleType = PARTICLE_TYPES[i]));

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
            .dimensions(this.width / 2 - 60, this.height - 28, 120, 20)
            .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Restore Defaults"), b -> {
            settings.resetDefaults();
            settings.save();
            if (this.client != null) {
                this.client.setScreen(new LyricSettingsScreen(parent));
            }
        }).dimensions(this.width / 2 - 160, this.height - 28, 95, 20).build());
    }

    @Override
    public void close() {
        settings.save();
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Avoid calling Screen#renderBackground here because some runtime combinations
        // already apply blur once per frame before screen render.
        context.fill(0, 0, this.width, this.height, 0xA0101010);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    private int findColorIndex(int rgb) {
        for (int i = 0; i < COLOR_OPTIONS.length; i++) {
            if (COLOR_OPTIONS[i] == rgb) {
                return i;
            }
        }
        return 0;
    }

    private int findParticleTypeIndex(String value) {
        for (int i = 0; i < PARTICLE_TYPES.length; i++) {
            if (PARTICLE_TYPES[i].equalsIgnoreCase(value)) {
                return i;
            }
        }
        return 0;
    }

    @FunctionalInterface
    private interface FloatSetter {
        void set(Double value);
    }

    private static class FloatSlider extends SliderWidget {
        private final String label;
        private final double min;
        private final double max;
        private final FloatSetter setter;

        protected FloatSlider(int x, int y, int width, int height, String label, double min, double max, double current, FloatSetter setter) {
            super(x, y, width, height, Text.literal(""), (current - min) / (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            double actual = min + value * (max - min);
            this.setMessage(Text.literal(label + ": " + String.format("%.2f", actual)));
        }

        @Override
        protected void applyValue() {
            double actual = min + value * (max - min);
            setter.set(actual);
        }
    }

    private static class IntSlider extends SliderWidget {
        private final String label;
        private final int min;
        private final int max;
        private final FloatSetter setter;

        protected IntSlider(int x, int y, int width, int height, String label, int min, int max, int current, FloatSetter setter) {
            super(x, y, width, height, Text.literal(""), (current - min) / (double) (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int actual = min + (int) Math.round(value * (max - min));
            this.setMessage(Text.literal(label + ": " + actual));
        }

        @Override
        protected void applyValue() {
            int actual = min + (int) Math.round(value * (max - min));
            setter.set((double) actual);
        }
    }
}
