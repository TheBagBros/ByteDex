package org.openmmo.bytedex.agent.patches;

import net.bytebuddy.asm.Advice;

/** Enables native town-map destinations while OpenMMO has no persisted story-flag progression. */
public final class FlyDestinationUnlockAdvice {

    private FlyDestinationUnlockAdvice() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.FieldValue(value = "iV", readOnly = false) boolean unlocked
    ) {
        // First-fire diagnostic (opt-in via -Dbytedex.debug; canonical name = DiagLog.DEBUG_PROPERTY).
        // Self-contained: only java.* + string literals so it stays loader-safe when inlined into the
        // client's isolated loader. Wrapped so it can never disturb the unlock below.
        try {
            String bytedexDebug = System.getProperty("bytedex.debug");
            if (bytedexDebug != null && !"false".equalsIgnoreCase(bytedexDebug)
                && !"0".equals(bytedexDebug)
                && System.getProperties().putIfAbsent("bytedex.fired.flyUnlock", "1") == null) {
                System.err.println("[bytedex] advice flyUnlock fired");
            }
        } catch (Throwable ignoredDiag) {
            // Diagnostics must never break a patch.
        }
        unlocked = true;
    }
}
