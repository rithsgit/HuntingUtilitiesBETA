package com.example.addon.mixin;

import com.example.addon.modules.SignScanner;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.client.render.block.entity.SignBlockEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SignBlockEntityRenderer.class)
public class MixinSignBlockEntityRenderer {
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/entity/SignBlockEntity;getFrontText()Lnet/minecraft/block/entity/SignText;"))
    private SignText redirectGetFrontText(SignBlockEntity sign) {
        SignText text = sign.getFrontText();
        SignScanner module = Modules.get().get(SignScanner.class);
        if (module != null && module.shouldCensor()) {
            return module.censorSignText(text);
        }
        return text;
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/entity/SignBlockEntity;getBackText()Lnet/minecraft/block/entity/SignText;"))
    private SignText redirectGetBackText(SignBlockEntity sign) {
        SignText text = sign.getBackText();
        SignScanner module = Modules.get().get(SignScanner.class);
        if (module != null && module.shouldCensor()) {
            return module.censorSignText(text);
        }
        return text;
    }
}