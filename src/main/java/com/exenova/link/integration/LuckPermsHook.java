package com.exenova.link.integration;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/** LuckPerms group lookups for Discord role sync. Only loaded when LuckPerms is installed. */
public final class LuckPermsHook {
    private final LuckPerms api;

    LuckPermsHook() { api = LuckPermsProvider.get(); }

    /** Every group the player inherits, including offline players (loaded asynchronously). */
    public CompletableFuture<Set<String>> groups(UUID uuid) {
        User online = api.getUserManager().getUser(uuid);
        CompletableFuture<User> user = online != null ? CompletableFuture.completedFuture(online) : api.getUserManager().loadUser(uuid);
        return user.thenApply(loaded -> loaded.resolveInheritedNodes(net.luckperms.api.query.QueryOptions.nonContextual()).stream()
                .filter(InheritanceNode.class::isInstance)
                .map(node -> ((InheritanceNode) node).getGroupName().toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toSet()));
    }

    public String primaryGroup(UUID uuid) {
        User user = api.getUserManager().getUser(uuid);
        return user == null ? "" : user.getPrimaryGroup();
    }
}
