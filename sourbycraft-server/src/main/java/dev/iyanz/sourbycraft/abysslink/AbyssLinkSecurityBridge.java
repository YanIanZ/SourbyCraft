package dev.iyanz.sourbycraft.abysslink;

import dev.iyanz.sourbycraft.security.SecurityGuards;
import java.util.Map;
import static dev.iyanz.sourbycraft.abysslink.AbyssLinkTlv.*;

/** Aggregate existing guard observations only. No policy mutation, bans, commands or addresses. */
public final class AbyssLinkSecurityBridge {
    private long previous;
    public static long violations() {
        long total=0;
        for(var limit:SecurityGuards.Limit.values())total+=SecurityGuards.INSTANCE.violations(limit);
        return total;
    }
    byte[] sample(boolean enabled) {
        long total=violations(),delta=Math.max(0,total-previous);previous=total;
        if(!enabled || delta==0)return null;
        return encode(Map.of(1,value("BACKEND_GUARD_VIOLATIONS"),2,u8(1),3,value(System.currentTimeMillis()),4,value("guard observations="+delta)));
    }
    static void validate(Map<Integer,byte[]> fields) {
        if(!string(fields,1,64).matches("[A-Z0-9_]{1,64}") || u8(fields,2)>10)throw new IllegalArgumentException("Invalid advisory signal");
        i64(fields,3);
        if(fields.containsKey(4))sanitized(string(fields,4,256));
        // Valid inbound observations are intentionally discarded; never alter backend admission.
    }
    static void sanitized(String text) {
        if(text.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c)==Character.FORMAT))throw new IllegalArgumentException("Unsanitized text");
    }
}
