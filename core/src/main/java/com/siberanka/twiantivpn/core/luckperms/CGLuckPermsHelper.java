package com.siberanka.twiantivpn.core.luckperms;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class CGLuckPermsHelper {
    public static CompletableFuture<Boolean> hasPermission(UUID uuid, String permission) {
        return CompletableFuture.supplyAsync(() -> {
            LuckPerms luckPerms = LuckPermsProvider.get();
            User user = luckPerms.getUserManager().loadUser(uuid).join();

            return user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
        });
    }
}
