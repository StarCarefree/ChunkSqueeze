package cn.star.chunksqueeze.mixin;

import cn.star.chunksqueeze.storage.StorageGuard;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Inject(method = "handleChunkLoadFailure", at = @At("HEAD"))
    private void chunksqueeze$preventRegeneration(Throwable failure, ChunkPos pos, CallbackInfoReturnable<ChunkAccess> cir) {
        StorageGuard.fail("Chunk load failed at " + pos + "; refusing regeneration", failure);
        StorageGuard.checkServer();
    }

    @Inject(method = "isChunkDataValid", at = @At("RETURN"))
    private static void chunksqueeze$rejectInvalidMetadata(CompoundTag tag, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() || tag.getString("Status").isEmpty()) {
            throw StorageGuard.fail("Invalid chunk metadata", new IllegalStateException("Missing or empty chunk Status"));
        }
    }

    @Inject(method = "markPositionReplaceable", at = @At("HEAD"))
    private void chunksqueeze$preventReplacement(ChunkPos pos, CallbackInfo ci) {
        StorageGuard.checkServer();
    }
}
