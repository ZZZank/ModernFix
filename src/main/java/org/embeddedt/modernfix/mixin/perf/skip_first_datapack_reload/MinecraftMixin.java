package org.embeddedt.modernfix.mixin.perf.skip_first_datapack_reload;

import com.mojang.datafixers.util.Function4;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.IResourceManager;
import net.minecraft.util.datafix.codec.DatapackCodec;
import net.minecraft.util.registry.DynamicRegistries;
import net.minecraft.world.storage.IServerConfiguration;
import net.minecraft.world.storage.SaveFormat;
import org.embeddedt.modernfix.ModernFix;
import org.embeddedt.modernfix.ModernFixClient;
import org.embeddedt.modernfix.duck.ILevelSave;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ExecutionException;
import java.util.function.Function;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow public abstract Minecraft.PackManager makeServerStem(DynamicRegistries.Impl dynamicRegistries, Function<SaveFormat.LevelSave, DatapackCodec> worldStorageToDatapackFunction, Function4<SaveFormat.LevelSave, DynamicRegistries.Impl, IResourceManager, DatapackCodec, IServerConfiguration> quadFunction, boolean vanillaOnly, SaveFormat.LevelSave worldStorage) throws InterruptedException, ExecutionException;

    @Shadow @Final private SaveFormat levelSource;

    @Redirect(method = "loadLevel(Ljava/lang/String;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/registry/DynamicRegistries;builtin()Lnet/minecraft/util/registry/DynamicRegistries$Impl;"))
    private DynamicRegistries.Impl useNullRegistry() {
        return null;
    }

    @Redirect(method = "loadWorld(Ljava/lang/String;Lnet/minecraft/util/registry/DynamicRegistries$Impl;Ljava/util/function/Function;Lcom/mojang/datafixers/util/Function4;ZLnet/minecraft/client/Minecraft$WorldSelectionType;Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;makeServerStem(Lnet/minecraft/util/registry/DynamicRegistries$Impl;Ljava/util/function/Function;Lcom/mojang/datafixers/util/Function4;ZLnet/minecraft/world/storage/SaveFormat$LevelSave;)Lnet/minecraft/client/Minecraft$PackManager;", ordinal = 0))
    private Minecraft.PackManager skipFirstReload(Minecraft client, DynamicRegistries.Impl dynamicRegistries, Function<SaveFormat.LevelSave, DatapackCodec> worldStorageToDatapackFunction, Function4<SaveFormat.LevelSave, DynamicRegistries.Impl, IResourceManager, DatapackCodec, IServerConfiguration> quadFunction, boolean vanillaOnly, SaveFormat.LevelSave levelSave, String worldName, DynamicRegistries.Impl originalRegistries, Function<SaveFormat.LevelSave, DatapackCodec> levelSaveToDatapackFunction, Function4<SaveFormat.LevelSave, DynamicRegistries.Impl, IResourceManager, DatapackCodec, IServerConfiguration> quadFunction2, boolean vanillaOnly2, Minecraft.WorldSelectionType selectionType, boolean creating) throws InterruptedException, ExecutionException {
        if(!creating) {
            ModernFix.LOGGER.warn("Skipping first reload, this is still experimental");
            ModernFix.runningFirstInjection = true;
            ((ILevelSave)levelSave).runWorldPersistenceHooks(levelSource);
            ModernFix.runningFirstInjection = false;
            return null;
        } else {
            /* allow reload */
            return makeServerStem(dynamicRegistries, worldStorageToDatapackFunction, quadFunction, vanillaOnly, levelSave);
        }
    }
}