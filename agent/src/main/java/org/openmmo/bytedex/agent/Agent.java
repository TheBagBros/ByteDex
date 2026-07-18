package org.openmmo.bytedex.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import org.openmmo.bytedex.agent.dns.DnsRedirectAdvice;
import org.openmmo.bytedex.agent.patches.CustomStringTableAdvice;
import org.openmmo.bytedex.agent.patches.FlyDestinationUnlockAdvice;
import org.openmmo.bytedex.agent.patches.ShinyBattleSparkleAdvice;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;

public final class Agent {

    private static final String DNS_RESOURCE = "/bytedex-dns.json";
    private static final String JAVA_PREFIX = "java.";
    private static final String JAVAX_PREFIX = "javax.";
    private static final String COM_SUN_PREFIX = "com.sun.";
    private static final String LWJGL_PREFIX = "org.lwjgl.";
    private static final String AGENT_PREFIX = "org.openmmo.bytedex.agent.";

    private Agent() {}

    public static void premain(String args, Instrumentation inst) {
        install(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        install(args, inst);
    }

    private static void install(String args, Instrumentation inst) {
        List<String> dnsRedirects = loadDnsRedirects();

        if (!dnsRedirects.isEmpty()) {
            System.setProperty(DnsRedirectAdvice.SYSTEM_PROPERTY, String.join(",", dnsRedirects));
        }

        AgentBuilder builder = new AgentBuilder.Default()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
            .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
            .ignore(
                ElementMatchers.nameStartsWith(JAVA_PREFIX)
                    .and(ElementMatchers.not(ElementMatchers.named("java.net.InetAddress")))
                    .or(ElementMatchers.nameStartsWith(JAVAX_PREFIX))
                    .or(ElementMatchers.nameStartsWith("jdk."))
                    .or(ElementMatchers.nameStartsWith("sun."))
                    .or(ElementMatchers.nameStartsWith(COM_SUN_PREFIX))
                    .or(ElementMatchers.nameStartsWith(LWJGL_PREFIX))
                    .or(ElementMatchers.nameStartsWith(AGENT_PREFIX))
            );

        builder = builder
            .type(ElementMatchers.not(ElementMatchers.nameStartsWith(JAVA_PREFIX)))
            .transform((b, type, cl, mod, pd) -> b.visit(
                Advice.to(VerifyBypassAdvice.class).on(
                    ElementMatchers.isStatic()
                        .and(ElementMatchers.returns(boolean.class))
                        .and(ElementMatchers.takesArguments(
                            byte[].class, byte[].class, PublicKey.class, String.class))
                )
            ));

        if (!dnsRedirects.isEmpty()) {
            builder = builder
                .type(ElementMatchers.is(InetAddress.class))
                .transform((b, type, cl, mod, pd) -> b.visit(
                    Advice.to(DnsRedirectAdvice.class).on(
                        ElementMatchers.isStatic()
                            .and(ElementMatchers.takesArguments(String.class))
                            .and(ElementMatchers.named("getByName")
                                .or(ElementMatchers.named("getAllByName")))
                    )
                ));
        }

        builder = builder
            .type(ElementMatchers.named("f.Q1"))
            .transform((b, type, cl, mod, pd) -> b.visit(
                Advice.to(CustomStringTableAdvice.class).on(
                    ElementMatchers.named("tO")
                        .and(ElementMatchers.takesArguments(boolean.class))
                )
            ));

        builder = builder
            .type(ElementMatchers.named("f.yC0"))
            .transform((b, type, cl, mod, pd) -> b.visit(
                Advice.to(FlyDestinationUnlockAdvice.class).on(
                    ElementMatchers.named("z40")
                        .and(ElementMatchers.takesArguments(0))
                )
            ));

        builder = builder
            .type(ElementMatchers.named("f.TQ"))
            .transform((b, type, cl, mod, pd) -> b.visit(
                Advice.to(ShinyBattleSparkleAdvice.class).on(
                    ElementMatchers.named("Com8")
                        .and(ElementMatchers.takesArguments(0))
                )
            ));

        builder.installOn(inst);

        if (!dnsRedirects.isEmpty()) {
            try {
                inst.retransformClasses(InetAddress.class);
            } catch (Throwable ignored) {
                // empty
            }
        }
        forceRetransformLoadedAppClasses(inst);

        if (args != null && !args.isEmpty()) {
            writeAttachReport(args, inst);
        }
    }

    private static void writeAttachReport(String filePath, Instrumentation inst) {
        long gameVersion = readGameVersion(inst);
        String json = "{\"gameVersion\":" + gameVersion + "}";
        try {
            Files.writeString(Path.of(filePath), json, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // empty
        }
    }

    private static long readGameVersion(Instrumentation inst) {
        Class<?> holder = findRevisionHolder(inst);
        if (holder == null) return 0L;
        try {
            for (Field f : holder.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                    f.setAccessible(true);
                    return ((Number) f.get(null)).longValue();
                }
            }
        } catch (Throwable ignored) {
            // empty
        }
        return 0L;
    }

    private static Class<?> findRevisionHolder(Instrumentation inst) {
        byte[] needle = "revision.txt".getBytes(StandardCharsets.UTF_8);
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String name = c.getName();
            if (name.startsWith("[") || name.startsWith(JAVA_PREFIX) || name.startsWith(JAVAX_PREFIX)
                || name.startsWith("jdk.") || name.startsWith("sun.") || name.startsWith(COM_SUN_PREFIX)
                || name.startsWith(LWJGL_PREFIX) || name.startsWith(AGENT_PREFIX)) {
                continue;
            }
            ClassLoader cl = c.getClassLoader();
            if (cl == null) continue;
            try (InputStream in = cl.getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in == null) continue;
                if (containsBytes(in.readAllBytes(), needle)) return c;
            } catch (Throwable ignored) {
                // empty
            }
        }
        return null;
    }

    private static boolean containsBytes(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static void forceRetransformLoadedAppClasses(Instrumentation inst) {
        List<Class<?>> targets = new ArrayList<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String name = c.getName();
            boolean excluded = name.startsWith(JAVA_PREFIX) || name.startsWith(JAVAX_PREFIX)
                || name.startsWith("jdk.") || name.startsWith("sun.")
                || name.startsWith(COM_SUN_PREFIX) || name.startsWith(LWJGL_PREFIX)
                || name.startsWith(AGENT_PREFIX)
                || name.startsWith("[");
            if (!excluded && inst.isModifiableClass(c)) {
                targets.add(c);
            }
        }
        int batch = 256;
        for (int i = 0; i < targets.size(); i += batch) {
            int end = Math.min(targets.size(), i + batch);
            try {
                inst.retransformClasses(targets.subList(i, end).toArray(new Class<?>[0]));
            } catch (Throwable ignored) {
                // empty
            }
        }
    }

    private static List<String> loadDnsRedirects() {
        List<String> out = new ArrayList<>();
        try (InputStream in = Agent.class.getResourceAsStream(DNS_RESOURCE)) {
            if (in == null) return out;
            JsonArray array = JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)
            ).getAsJsonArray();
            for (JsonElement el : array) {
                String s = el.getAsString();
                if (s != null && !s.isEmpty()) out.add(s);
            }
        } catch (Exception ignored) {
            // empty
        }
        return out;
    }
}
