package cn.star.chunksqueeze.mixin;

import cn.star.chunksqueeze.migration.MigrationService;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Shadow @Final private MinecraftServer server;

    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void chunksqueeze$rejectDuringMigration(SocketAddress address, GameProfile profile, CallbackInfoReturnable<Component> cir) {
        Component reason = MigrationService.rejection(server);
        if (reason != null) cir.setReturnValue(reason);
    }

    @Inject(method = "placeNewPlayer", at = @At("HEAD"), cancellable = true)
    private void chunksqueeze$guardFinalAdmission(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        Component reason = MigrationService.rejection(server);
        if (reason != null) {
            // 最终入口再检查，直接关闭连接，不向不确定的协议阶段发送数据包。
            connection.disconnect(reason);
            ci.cancel();
        }
    }
}
