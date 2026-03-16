package com.example.mixin;

import com.example.RTPR;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class ServerMixin {

    @Inject(method = "runServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;initServer()Z"))
    private void onServerReady(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        RTPR.serverStarted(server);
    }
}