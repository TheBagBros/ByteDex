package org.openmmo.bytedex.agent.dns;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.net.InetAddress;

public final class DnsRedirectAdvice {

    public static final String SYSTEM_PROPERTY = "bytedex.dnsRedirects";

    private DnsRedirectAdvice() {}

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Object enter(
        @Advice.Argument(0) String host,
        @Advice.Origin("#m") String methodName
    ) {
        if (host == null) return null;
        String list = System.getProperty(SYSTEM_PROPERTY);
        if (list == null || list.isEmpty()) return null;

        int start = 0;
        boolean matched = false;
        int len = list.length();
        while (start <= len) {
            int end = list.indexOf(',', start);
            if (end < 0) end = len;
            int segLen = end - start;
            if (segLen == host.length()
                && list.regionMatches(true, start, host, 0, segLen)) {
                matched = true;
                break;
            }
            start = end + 1;
        }
        if (!matched) return null;

        // First-fire diagnostic (opt-in via -Dbytedex.debug; canonical name = DiagLog.DEBUG_PROPERTY).
        // NOTE: this advice is inlined into bootstrap-loaded java.net.InetAddress, so it may reference
        // ONLY java.* + string literals -- an agent helper class would NoClassDefFoundError here.
        // Wrapped so it can never disturb the redirect below.
        try {
            String bytedexDebug = System.getProperty("bytedex.debug");
            if (bytedexDebug != null && !"false".equalsIgnoreCase(bytedexDebug)
                && !"0".equals(bytedexDebug)
                && System.getProperties().putIfAbsent("bytedex.fired.dnsRedirect", "1") == null) {
                System.err.println("[bytedex] advice dnsRedirect fired");
            }
        } catch (Throwable ignoredDiag) {
            // Diagnostics must never break a patch.
        }

        try {
            InetAddress addr = InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1});
            if ("getAllByName".equals(methodName)) {
                return new InetAddress[]{addr};
            }
            return addr;
        } catch (Exception e) {
            return null;
        }
    }

    @Advice.OnMethodExit
    @SuppressWarnings({"java:S1226", "java:S1854"})
    public static void exit(
        @Advice.Enter Object entered,
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned
    ) {
        if (entered != null) {
            returned = entered;
        }
    }
}
