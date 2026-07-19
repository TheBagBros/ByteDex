package org.openmmo.bytedex.agent;

import net.bytebuddy.asm.Advice;

public final class VerifyBypassAdvice {

    private VerifyBypassAdvice() {}

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int enter() {
        // First-fire diagnostic (opt-in via -Dbytedex.debug; canonical name = DiagLog.DEBUG_PROPERTY).
        // Self-contained: only java.* + string literals so it stays loader-safe when this advice is
        // inlined into the client's classes. Wrapped so it can never disturb the bypass below.
        try {
            String bytedexDebug = System.getProperty("bytedex.debug");
            if (bytedexDebug != null && !"false".equalsIgnoreCase(bytedexDebug)
                && !"0".equals(bytedexDebug)
                && System.getProperties().putIfAbsent("bytedex.fired.verifyBypass", "1") == null) {
                System.err.println("[bytedex] advice verifyBypass fired");
            }
        } catch (Throwable ignoredDiag) {
            // Diagnostics must never break a patch.
        }
        return 1;
    }

    @Advice.OnMethodExit
    @SuppressWarnings({"unused", "java:S1226"})
    public static void exit(@Advice.Return(readOnly = false) boolean returned) {
        returned = true;
    }
}
