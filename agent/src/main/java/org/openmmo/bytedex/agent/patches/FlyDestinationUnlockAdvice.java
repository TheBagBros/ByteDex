package org.openmmo.bytedex.agent.patches;

import net.bytebuddy.asm.Advice;

/** Enables native town-map destinations while OpenMMO has no persisted story-flag progression. */
public final class FlyDestinationUnlockAdvice {

    private FlyDestinationUnlockAdvice() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.FieldValue(value = "iV", readOnly = false) boolean unlocked
    ) {
        unlocked = true;
    }
}
