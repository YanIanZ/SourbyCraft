package dev.iyanz.sourbycraft.abysslink;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Fallback is represented by an empty result; this resolver never accesses game state. */
public final class AbyssLinkPingResolver {
    private AbyssLinkPingResolver(){}
    public static boolean current(AbyssLinkClient.Sample sample) {
        var client=AbyssLinkRuntime.client();return client!=null && client.current(sample);
    }
    public static CompletableFuture<Optional<AbyssLinkClient.Sample>> resolve(UUID player) {
        var client=AbyssLinkRuntime.client();
        return client==null || !dev.iyanz.sourbycraft.security.SecurityGuards.INSTANCE.proxyForwardingConfigured()?CompletableFuture.completedFuture(Optional.empty()):client.resolve(player);
    }
}
