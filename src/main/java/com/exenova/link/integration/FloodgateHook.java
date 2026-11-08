package com.exenova.link.integration;

import java.util.UUID;

/** Floodgate (Geyser) player detection via reflection on {@code org.geysermc.floodgate.api.FloodgateApi}. */
final class FloodgateHook {
    private final Object api;

    FloodgateHook() {
        try {
            api = Class.forName("org.geysermc.floodgate.api.FloodgateApi").getMethod("getInstance").invoke(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Floodgate API not found", exception);
        }
    }

    boolean isBedrock(UUID uuid) {
        Object result = Integrations.call(api, "isFloodgatePlayer", uuid);
        return result instanceof Boolean bool ? bool : uuid.getMostSignificantBits() == 0;
    }
}
