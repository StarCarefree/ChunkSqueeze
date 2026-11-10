package cn.star.chunksqueeze.mixin;

import cn.star.chunksqueeze.storage.StorageGuard;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.BitSet;
import java.util.concurrent.CompletableFuture;

@Mixin(IOWorker.class)
public abstract class IOWorkerMixin {
    @Inject(method = "createOldDataForRegion", at = @At("RETURN"), cancellable = true)
    private void chunksqueeze$rejectFailedScan(int x, int z, CallbackInfoReturnable<CompletableFuture<BitSet>> cir) {
        cir.setReturnValue(cir.getReturnValue().thenApply(bits -> {
            StorageGuard.check();
            return bits;
        }));
    }
}
