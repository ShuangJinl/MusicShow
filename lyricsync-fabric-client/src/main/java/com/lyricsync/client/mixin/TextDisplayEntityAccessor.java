package com.lyricsync.client.mixin;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DisplayEntity.TextDisplayEntity.class)
public interface TextDisplayEntityAccessor {
    @Invoker("setText")
    void lyricsync$setText(Text text);

    @Invoker("setBackground")
    void lyricsync$setBackground(int background);

    @Invoker("setTextOpacity")
    void lyricsync$setTextOpacity(byte opacity);

    @Invoker("setDisplayFlags")
    void lyricsync$setFlags(byte flags);
}
