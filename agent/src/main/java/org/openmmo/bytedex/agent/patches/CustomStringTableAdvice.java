package org.openmmo.bytedex.agent.patches;

import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

/** Injects OpenMMO-custom UI strings that do not exist in the frozen client's xR1 table. */
public final class CustomStringTableAdvice {

    private static final int SHINY_CHARM_NAME_KEY = 101411;
    private static final String SHINY_CHARM_NAME = "Shiny Charm";
    private static final String STRING_REGISTRY_CLASS = "f.nV0";
    private static final String REGISTER_METHOD = "Gv1";

    private CustomStringTableAdvice() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.Origin Class<?> origin) throws ReflectiveOperationException {
        // First-fire diagnostic (opt-in via -Dbytedex.debug; canonical name = DiagLog.DEBUG_PROPERTY).
        // Self-contained: only java.* + string literals so it stays loader-safe when inlined into the
        // client's isolated loader. Wrapped so it can never disturb the injection below.
        try {
            String bytedexDebug = System.getProperty("bytedex.debug");
            if (bytedexDebug != null && !"false".equalsIgnoreCase(bytedexDebug)
                && !"0".equals(bytedexDebug)
                && System.getProperties().putIfAbsent("bytedex.fired.customString", "1") == null) {
                System.err.println("[bytedex] advice customString fired");
            }
        } catch (Throwable ignoredDiag) {
            // Diagnostics must never break a patch.
        }

        ClassLoader loader = origin == null ? null : origin.getClassLoader();
        Class<?> strings = Class.forName(STRING_REGISTRY_CLASS, false, loader);
        Method register = strings.getDeclaredMethod(REGISTER_METHOD, int.class, String.class);
        register.trySetAccessible();
        register.invoke(null, SHINY_CHARM_NAME_KEY, SHINY_CHARM_NAME);
    }
}
