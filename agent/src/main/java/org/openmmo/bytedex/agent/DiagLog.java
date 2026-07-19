package org.openmmo.bytedex.agent;

import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Opt-in diagnostic logging for the ByteDex agent, gated behind {@code -Dbytedex.debug}.
 *
 * <p><b>Why the diagnostics are split (read before adding logging elsewhere):</b> this class runs
 * ONLY in the agent's own (system) classloader, driven from {@link Agent#premain}. It MUST NOT be
 * referenced from {@code @Advice} code. Advice bytecode is <i>inlined</i> into the frozen client's
 * isolated classloader (and, for {@code DnsRedirectAdvice}, into bootstrap-loaded
 * {@code java.net.InetAddress}), where agent classes are NOT reachable. A reference to {@code DiagLog}
 * from inlined advice would raise {@code NoClassDefFoundError}, which every advice's
 * {@code suppress = Throwable.class} would silently swallow &mdash; producing exactly the invisible
 * dead patch this hook exists to prevent. The established codebase pattern already proves this:
 * {@code DnsRedirectAdvice} passes its redirect list across the loader boundary through a
 * <i>system property</i>, not a shared agent field.
 *
 * <p>Advice sites therefore do their own per-advice first-fire logging <b>inline</b>, using only
 * {@code java.*} calls and String literals (constant expressions are inlined as literals per JLS
 * 13.1, so they carry no runtime class reference). The constants below document the canonical
 * property/prefix names those inline blocks use; they are intentionally duplicated as literals at
 * the advice sites so the advice code stays self-contained and loader-safe.
 */
public final class DiagLog {

    /**
     * JVM flag that turns on all diagnostics: {@code -Dbytedex.debug=true} (a bare
     * {@code -Dbytedex.debug} also enables it; {@code =false} or {@code =0} keeps it off).
     */
    public static final String DEBUG_PROPERTY = "bytedex.debug";

    /** Prefix of the per-advice first-fire dedup keys stashed in system properties. */
    public static final String FIRED_PREFIX = "bytedex.fired.";

    /** Common log-line prefix shared by the premain summary and the inline first-fire lines. */
    public static final String LOG_PREFIX = "[bytedex] ";

    private static final String PREMAIN_CLASS = "org.openmmo.bytedex.agent.Agent";

    private DiagLog() {}

    /**
     * True when {@code -Dbytedex.debug} is set to anything other than {@code false}/{@code 0}.
     * Safe to call from any classloader (touches only {@code java.lang.System}).
     */
    public static boolean enabled() {
        try {
            String v = System.getProperty(DEBUG_PROPERTY);
            return v != null && !v.equalsIgnoreCase("false") && !v.equals("0");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Emits the premain summary: the agent build id and every transformer/advice that was
     * registered. No-op unless diagnostics are enabled. Never throws.
     */
    public static void premainSummary(String build, List<String> registrations) {
        if (!enabled()) return;
        try {
            System.err.println(LOG_PREFIX + "agent attached (premain) build=" + build
                + " diagnostics=ON via -D" + DEBUG_PROPERTY);
            System.err.println(LOG_PREFIX + "registered " + registrations.size() + " transformer(s):");
            for (String r : registrations) {
                System.err.println(LOG_PREFIX + "  - " + r);
            }
            System.err.println(LOG_PREFIX
                + "each advice logs \"advice <id> fired\" the first time it runs in the client");
        } catch (Throwable ignored) {
            // Diagnostics must never disturb agent startup.
        }
    }

    /**
     * Reads this agent jar's build id from its own manifest ({@code Implementation-Version}),
     * located by matching {@code Premain-Class}. Returns {@code "unknown"} if unavailable.
     */
    public static String agentBuild() {
        try {
            Enumeration<URL> manifests =
                DiagLog.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
            while (manifests.hasMoreElements()) {
                try (InputStream in = manifests.nextElement().openStream()) {
                    Attributes attrs = new Manifest(in).getMainAttributes();
                    if (PREMAIN_CLASS.equals(attrs.getValue("Premain-Class"))) {
                        String version = attrs.getValue("Implementation-Version");
                        return version != null ? version : "unknown";
                    }
                } catch (Throwable ignored) {
                    // Skip an unreadable manifest and keep scanning.
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the default below.
        }
        return "unknown";
    }
}
