package com.momosoftworks.coldsweat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.momosoftworks.coldsweat.ColdSweat;
import com.momosoftworks.coldsweat.config.ClientSettingsConfig;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.Surrogate;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Gui.class)
public class MixinXPBar
{
    /**
     * @author iMikul
     * @reason Move XP bar number to make room for body temperature readout (2 methods needed)
     */
    @Inject(method = "renderExperienceBar",
            at = @At
            (   value = "INVOKE",
                target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V",
                shift = At.Shift.AFTER
            ),
            slice = @Slice
            (   from = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V"),
                to   = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;width(Ljava/lang/String;)I")
            ),
            remap = ColdSweat.REMAP_MIXINS)
    public void renderExperienceBar1(GuiGraphics graphics, int x, CallbackInfo ci)
    {
        // Render XP bar
        if (ClientSettingsConfig.getInstance().customHotbarEnabled())
        {   graphics.pose().translate(0.0D, 4.0D, 0.0D);
        }
    }

    @Inject(method = "renderExperienceBar",
            at = @At
            (   value = "INVOKE",
                target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V"
            ),
            slice = @Slice
            (   from = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;width(Ljava/lang/String;)I"),
                to   = @At(value = "RETURN")
            ),
            remap = ColdSweat.REMAP_MIXINS)
    public void renderExperienceBar2(GuiGraphics graphics, int px, CallbackInfo ci)
    {
        // Render XP bar
        if (ClientSettingsConfig.getInstance().customHotbarEnabled())
        {   graphics.pose().translate(0.0D, -4.0D, 0.0D);
        }
    }

    @Mixin(Gui.class)
    public static class MixinItemLabel
    {
        private static boolean MOVED_UP = false;

        @Surrogate
        @Inject(method = "renderSelectedItemName*",
                at = @At(value = "HEAD"),
                remap = ColdSweat.REMAP_MIXINS)
        public void renderItemNamePre(GuiGraphics graphics, CallbackInfo ci)
        {
            if (!MOVED_UP && ClientSettingsConfig.getInstance().customHotbarEnabled())
            {   graphics.pose().translate(0, -4, 0);
                MOVED_UP = true;
            }
        }

        @Inject(method = "renderSelectedItemName*",
                at = @At(value = "HEAD"),
                remap = ColdSweat.REMAP_MIXINS)
        public void renderItemNamePre(GuiGraphics graphics, int height, CallbackInfo ci)
        {
            if (!MOVED_UP && ClientSettingsConfig.getInstance().customHotbarEnabled())
            {   graphics.pose().translate(0, -4, 0);
                MOVED_UP = true;
            }
        }

        @Surrogate
        @Inject(method = "renderSelectedItemName*",
                at = @At("TAIL"),
                remap = ColdSweat.REMAP_MIXINS)
        public void renderItemNamePost(GuiGraphics graphics, CallbackInfo ci)
        {
            if (MOVED_UP && ClientSettingsConfig.getInstance().customHotbarEnabled())
            {   graphics.pose().translate(0, 4, 0);
                MOVED_UP = false;
            }
        }

        @Inject(method = "renderSelectedItemName*",
                at = @At("TAIL"),
                remap = ColdSweat.REMAP_MIXINS)
        public void renderItemNamePost(GuiGraphics graphics, int height, CallbackInfo ci)
        {
            if (MOVED_UP && ClientSettingsConfig.getInstance().customHotbarEnabled())
            {   graphics.pose().translate(0, 4, 0);
                MOVED_UP = false;
            }
        }
    }
}
