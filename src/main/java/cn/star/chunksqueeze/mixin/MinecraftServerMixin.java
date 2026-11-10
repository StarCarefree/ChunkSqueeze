package cn.star.chunksqueeze.mixin;

import cn.star.chunksqueeze.Chunksqueeze;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void chunksqueeze$awaitStorageWorkers(CallbackInfo ci) {
        Chunksqueeze.stopStorageWorkers((MinecraftServer) (Object) this);
    }
}
