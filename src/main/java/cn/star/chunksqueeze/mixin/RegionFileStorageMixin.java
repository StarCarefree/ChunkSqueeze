package cn.star.chunksqueeze.mixin;

import cn.star.chunksqueeze.Config;
import cn.star.chunksqueeze.codec.ChunkCodec;
import cn.star.chunksqueeze.codec.SectionCodec;
import cn.star.chunksqueeze.migration.MigrationGate;
import cn.star.chunksqueeze.migration.MigrationStorageAccess;
import cn.star.chunksqueeze.storage.CompactRegionStore;
import cn.star.chunksqueeze.storage.BackgroundReencoder;
import cn.star.chunksqueeze.storage.StorageGuard;
import cn.star.chunksqueeze.storage.RegionIntegrity;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ExceptionCollector;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin implements MigrationStorageAccess {
    @Shadow @Final private Path folder;
    @Shadow @Final private RegionStorageInfo info;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<RegionFile> regionCache;
    @Shadow public abstract CompoundTag read(ChunkPos pos) throws IOException;
    @Shadow private RegionFile getRegionFile(ChunkPos pos) throws IOException { throw new AssertionError(); }
    @Unique private boolean chunksqueeze$marked;
    @Unique private boolean chunksqueeze$closed;
    @Unique private CompactRegionStore chunksqueeze$compact;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunksqueeze$register(RegionStorageInfo storageInfo, Path directory, boolean sync, CallbackInfo ci) {
        if (!info.type().startsWith("chunk")) return;
        try { MigrationGate.register(this); }
        catch (IOException | RuntimeException failure) { throw StorageGuard.fail(folder.toString(), failure); }
    }

    @Override
    public Path chunksqueeze$migrationFolder() { return folder; }

    @Override
    public void chunksqueeze$closeRegionForMigration(int regionX, int regionZ) throws IOException {
        MigrationGate.requireExclusive();
        ExceptionCollector<IOException> failures = new ExceptionCollector<>();
        RegionFile nativeRegion = regionCache.remove(ChunkPos.asLong(regionX, regionZ));
        if (nativeRegion != null) try { nativeRegion.close(); } catch (IOException failure) { failures.add(failure); }
        if (chunksqueeze$compact != null) {
            try { chunksqueeze$compact.closeRegion(regionX, regionZ); } catch (IOException failure) { failures.add(failure); }
        }
        failures.throwIfPresent();
    }

    @Unique
    private CompactRegionStore chunksqueeze$store() {
        if (chunksqueeze$compact == null) chunksqueeze$compact = new CompactRegionStore(folder, BackgroundReencoder.current());
        return chunksqueeze$compact;
    }

    @Inject(method = "getRegionFile", at = @At(value = "NEW", target = "net/minecraft/world/level/chunk/storage/RegionFile"))
    private void chunksqueeze$validateHeader(ChunkPos pos, CallbackInfoReturnable<RegionFile> cir) {
        StorageGuard.check();
        if (!info.type().startsWith("chunk")) return;
        try { RegionIntegrity.validateHeader(folder.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca")); }
        catch (IOException | RuntimeException failure) { throw StorageGuard.fail(folder + " " + pos, failure); }
    }

    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void chunksqueeze$write(ChunkPos pos, CompoundTag original, CallbackInfo ci) {
        StorageGuard.check();
        if (!info.type().startsWith("chunk")) return;
        try (MigrationGate.Lease ignored = MigrationGate.region(folder, pos.getRegionX(), pos.getRegionZ())) {
            if (chunksqueeze$closed) throw new IOException("Region storage is closed");
            CompactRegionStore compact = chunksqueeze$store();
            boolean existingCompact = compact.owns(pos);
            if (existingCompact && !info.type().equals("chunk")) throw new IOException("Export compact regions before region recreation");
            boolean useCompact = existingCompact || (Config.compactRegions && info.type().equals("chunk") && !compact.hasMca(pos));
            if (useCompact) {
                if (original != null || compact.owns(pos)) {
                    chunksqueeze$markFormat();
                    if (original == null) compact.write(pos, null);
                    else {
                        compact.writeDeferred(pos, original,
                                new ChunkCodec.Options(Config.minimumSaving, Config.requireSectorSaving));
                    }
                    StorageGuard.compactWritten();
                }
            } else {
                CompoundTag stored = chunksqueeze$encode(original, false);
                RegionFile region = getRegionFile(pos);
                if (stored == null) region.clear(pos);
                else try (var output = region.getChunkDataOutputStream(pos)) { NbtIo.write(stored, output); }
            }
            ci.cancel();
        } catch (IOException | RuntimeException failure) {
            throw StorageGuard.fail(folder + " " + pos, failure);
        }
    }

    @Unique
    private CompoundTag chunksqueeze$encode(CompoundTag original, boolean compact) throws IOException {
        if (original == null) return null;
        if (original.contains(ChunkCodec.KEY) || original.contains(SectionCodec.KEY)) throw new IOException("Encoded NBT reached the write API");
        if (!original.contains("sections", Tag.TAG_LIST) || !original.contains("Status", Tag.TAG_STRING)
                || (!compact && RegionFileVersion.getSelected() != RegionFileVersion.VERSION_DEFLATE)) return original;
        int unit = compact ? 512 : 4096;
        int overhead = compact ? 0 : 5;
        ChunkCodec.Result result = ChunkCodec.encode(original,
                new ChunkCodec.Options(Config.minimumSaving, Config.requireSectorSaving), unit, overhead);
        if (result.optimized()) chunksqueeze$markFormat();
        StorageGuard.encoded(result, unit, overhead);
        return result.tag();
    }

    @Unique
    private void chunksqueeze$markFormat() throws IOException {
        if (chunksqueeze$marked) return;
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("CHUNKSQUEEZE_FORMAT.txt"),
                "ChunkSqueeze: state formats 1/2 / section format 1 / compact region formats 1/2. Keep the decoder installed.\n"
                        + "Do not open this world without ChunkSqueeze.\n"
                        + "Stop the server and use tools/restore_world.py to export a standard world before uninstalling.\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        chunksqueeze$marked = true;
    }

    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private void chunksqueeze$decode(ChunkPos pos, CallbackInfoReturnable<CompoundTag> cir) {
        StorageGuard.check();
        if (!info.type().startsWith("chunk")) return;
        try (MigrationGate.Lease ignored = MigrationGate.region(folder, pos.getRegionX(), pos.getRegionZ())) {
            if (chunksqueeze$closed) throw new IOException("Region storage is closed");
            CompactRegionStore compact = chunksqueeze$store();
            CompoundTag stored;
            if (compact.hasOpenRegion(pos) || (!regionCache.containsKey(ChunkPos.asLong(pos.getRegionX(), pos.getRegionZ())) && compact.owns(pos))) {
                if (!info.type().equals("chunk")) throw new IOException("Export compact regions before region recreation");
                stored = compact.read(pos);
            } else if (!regionCache.containsKey(ChunkPos.asLong(pos.getRegionX(), pos.getRegionZ()))
                    && Config.compactRegions && info.type().equals("chunk") && !compact.hasMca(pos)) {
                stored = null;
            } else {
                RegionFile region = getRegionFile(pos);
                boolean present = region.hasChunk(pos);
                try (var stream = region.getChunkDataInputStream(pos)) {
                    if (stream == null) {
                        if (present) throw new IOException("Existing chunk record could not be read");
                        stored = null;
                    } else {
                        stored = NbtIo.read(stream, NbtAccounter.unlimitedHeap());
                    }
                }
            }
            if (stored == null) cir.setReturnValue(null);
            else {
                CompoundTag decoded = SectionCodec.decode(stored, null);
                if (stored.contains(ChunkCodec.KEY)) StorageGuard.decoded();
                cir.setReturnValue(decoded);
            }
        } catch (IOException | RuntimeException failure) {
            throw StorageGuard.fail(folder + " " + pos, failure);
        }
    }

    @Inject(method = "scanChunk", at = @At("HEAD"), cancellable = true)
    private void chunksqueeze$scan(ChunkPos pos, StreamTagVisitor visitor, CallbackInfo ci) throws IOException {
        StorageGuard.check();
        if (!info.type().startsWith("chunk")) return;
        CompoundTag tag = read(pos);
        if (tag != null) tag.acceptAsRoot(visitor);
        ci.cancel();
    }

    @Inject(method = "flush", at = @At("HEAD"), cancellable = true)
    private void chunksqueeze$flush(CallbackInfo ci) {
        if (!info.type().startsWith("chunk")) return;
        try (MigrationGate.Lease ignored = MigrationGate.access()) {
            if (chunksqueeze$closed) throw new IOException("Region storage is closed");
            if (chunksqueeze$compact != null && !StorageGuard.isFailed()) chunksqueeze$compact.flush();
            for (RegionFile region : regionCache.values()) region.flush();
            ci.cancel();
        } catch (IOException | RuntimeException failure) { throw StorageGuard.fail(folder.toString(), failure); }
    }

    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void chunksqueeze$close(CallbackInfo ci) throws IOException {
        if (!info.type().startsWith("chunk")) return;
        try (MigrationGate.Lease ignored = MigrationGate.access()) {
            if (!chunksqueeze$closed) {
                chunksqueeze$closed = true;
                ExceptionCollector<IOException> failures = new ExceptionCollector<>();
                if (chunksqueeze$compact != null) {
                    try { chunksqueeze$compact.close(); } catch (IOException failure) { failures.add(failure); }
                }
                for (RegionFile region : regionCache.values()) {
                    try { region.close(); } catch (IOException failure) { failures.add(failure); }
                }
                regionCache.clear();
                failures.throwIfPresent();
            }
            ci.cancel();
        } finally {
            MigrationGate.unregister(this);
        }
    }
}
