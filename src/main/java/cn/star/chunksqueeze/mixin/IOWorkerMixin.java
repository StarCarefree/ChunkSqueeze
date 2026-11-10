package cn.star.chunksqueeze.mixin;

import cn.star.chunksqueeze.compat.StorageKindAccess;
import cn.star.chunksqueeze.storage.StorageGuard;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.BitSet;
import java.util.concurrent.CompletableFuture;

@Mixin(IOWorker.class)
public abstract class IOWorkerMixin {
    @Shadow @Final private RegionFileStorage storage;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunksqueeze$setStorageKind(Path folder, boolean sync, String name, CallbackInfo ci) {
        // 旧版没有 RegionStorageInfo，由 IOWorker 提供地形、实体和 POI 类型。
        ((StorageKindAccess) (Object) storage).chunksqueeze$setStorageKind(name);
    }

    @Inject(method = "createOldDataForRegion", at = @At("RETURN"), cancellable = true)
    private void chunksqueeze$rejectFailedScan(int x, int z, CallbackInfoReturnable<CompletableFuture<BitSet>> cir) {
        cir.setReturnValue(cir.getReturnValue().thenApply(bits -> {
            StorageGuard.check();
            return bits;
        }));
    }
}
