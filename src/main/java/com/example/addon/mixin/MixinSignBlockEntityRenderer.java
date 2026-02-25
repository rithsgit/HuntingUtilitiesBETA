package com.example.addon.mixin;

import com.example.addon.modules.SignScanner;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SignBlockEntity.class)
public class MixinSignBlockEntityRenderer {
    @Inject(method = "getFrontText", at = @At("RETURN"), cancellable = true)
    private void onGetFrontText(CallbackInfoReturnable<SignText> cir) {
        SignScanner module = Modules.get().get(SignScanner.class);
        if (module != null && module.shouldCensor()) {
            cir.setReturnValue(module.censorSignText(cir.getReturnValue()));
        }
    }

    @Inject(method = "getBackText", at = @At("RETURN"), cancellable = true)
    private void onGetBackText(CallbackInfoReturnable<SignText> cir) {
        SignScanner module = Modules.get().get(SignScanner.class);
        if (module != null && module.shouldCensor()) {
            cir.setReturnValue(module.censorSignText(cir.getReturnValue()));
        }
    }
}