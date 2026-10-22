package dev.iyanz.sourbycraft.awf.redis;

/** For in-memory Redis stubs: recognises a script call whether it is sent as {@code EVAL} or {@code EVALSHA}. */
final class StubScripts {
    private StubScripts() {}

    /** Whether {@code command} runs {@code script}, by text ({@code EVAL}) or by SHA-1 ({@code EVALSHA}). */
    static boolean is(final Object[] command, final String script) {
        if ("EVAL".equals(command[0])) return script.equals(command[1]);
        return "EVALSHA".equals(command[0]) && RedisClient.sha1(script).equals(command[1]);
    }

    /** Whether {@code command} runs any script. */
    static boolean isScript(final Object[] command) {
        return "EVAL".equals(command[0]) || "EVALSHA".equals(command[0]);
    }
}
