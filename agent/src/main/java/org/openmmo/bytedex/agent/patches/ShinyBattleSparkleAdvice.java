package org.openmmo.bytedex.agent.patches;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Plays the frozen client's built-in star effect and sound during each shiny battle entrance. */
public final class ShinyBattleSparkleAdvice {

    private static final String MONSTER_FIELD = "pi";
    private static final String SHINY_METHOD = "Dk0";
    private static final String EFFECT_METHOD = "cH1";
    private static final String CREATE_TIMELINE_METHOD = "QS0";
    private static final String BEGIN_GROUP_METHOD = "J6";
    private static final String APPEND_METHOD = "OR";
    private static final String APPEND_TIMELINE_METHOD = "Ix0";
    private static final String DELAY_METHOD = "ih1";
    private static final String END_GROUP_METHOD = "Zy1";
    private static final String TOTAL_DURATION_METHOD = "LPT4";
    private static final String CHILDREN_FIELD = "zo";
    private static final String CALLBACK_FIELD = "Xg";
    private static final String START_DELAY_FIELD = "gh0";
    private static final String SOUND_METHOD = "yc";
    private static final String EFFECT_NAME = "shiny_star_prebaked";
    private static final short SOUND_ID = 250;
    private static final float DROP_START_DELAY_AFTER_SPAWN = 0.4F;

    private ShinyBattleSparkleAdvice() {}

    @SuppressWarnings("java:S3776") // Advice must be fully inlined into the isolated client loader.
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.This Object sendOut,
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object timeline
    ) throws ReflectiveOperationException {
        if (sendOut == null || timeline == null) return;

        Field monsterField = null;
        for (Class<?> current = sendOut.getClass(); current != null;
             current = current.getSuperclass()) {
            try {
                monsterField = current.getDeclaredField(MONSTER_FIELD);
                monsterField.trySetAccessible();
                break;
            } catch (NoSuchFieldException ignored) {
                // Continue through the obfuscated client's class hierarchy.
            }
        }
        if (monsterField == null) return;

        Object monster = monsterField.get(sendOut);
        if (monster == null) return;

        Method shinyMethod = null;
        for (Class<?> current = monster.getClass(); current != null;
             current = current.getSuperclass()) {
            try {
                shinyMethod = current.getDeclaredMethod(SHINY_METHOD);
                shinyMethod.trySetAccessible();
                break;
            } catch (NoSuchMethodException ignored) {
                // Continue through the obfuscated client's class hierarchy.
            }
        }
        if (shinyMethod == null || !Boolean.TRUE.equals(shinyMethod.invoke(monster))) return;

        Method effectMethod = null;
        for (Class<?> current = sendOut.getClass(); current != null;
             current = current.getSuperclass()) {
            try {
                effectMethod = current.getDeclaredMethod(EFFECT_METHOD, String.class);
                effectMethod.trySetAccessible();
                break;
            } catch (NoSuchMethodException ignored) {
                // Continue through the obfuscated client's class hierarchy.
            }
        }
        if (effectMethod == null) return;

        Object sparkle = effectMethod.invoke(sendOut, EFFECT_NAME);
        if (sparkle == null) return;

        Method createTimeline = null;
        Method beginGroup = null;
        Method appendTimeline = null;
        Method appendEffect = null;
        Method delay = null;
        Method endGroup = null;
        for (Class<?> current = timeline.getClass(); current != null;
             current = current.getSuperclass()) {
            try {
                createTimeline = current.getDeclaredMethod(CREATE_TIMELINE_METHOD);
                beginGroup = current.getDeclaredMethod(BEGIN_GROUP_METHOD);
                appendTimeline = current.getDeclaredMethod(APPEND_TIMELINE_METHOD, timeline.getClass());
                delay = current.getDeclaredMethod(DELAY_METHOD, float.class);
                endGroup = current.getDeclaredMethod(END_GROUP_METHOD);
                createTimeline.trySetAccessible();
                beginGroup.trySetAccessible();
                appendTimeline.trySetAccessible();
                delay.trySetAccessible();
                endGroup.trySetAccessible();
                break;
            } catch (NoSuchMethodException ignored) {
                // Continue through the obfuscated client's class hierarchy.
            }
        }
        for (Class<?> current = timeline.getClass(); current != null && appendEffect == null;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(APPEND_METHOD) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(sparkle)) {
                    method.trySetAccessible();
                    appendEffect = method;
                    break;
                }
            }
        }
        if (createTimeline == null || beginGroup == null || appendTimeline == null
            || appendEffect == null || delay == null || endGroup == null
            ) return;

        // Read the unstarted timeline without finalizing or mutating it. Finalizing here makes the
        // client's later animation setup reuse already-offset children and corrupts the send-out.
        Map<Object, List<?>> childrenByNode = new IdentityHashMap<>();
        Map<Object, Integer> modeByNode = new IdentityHashMap<>();
        Map<Object, Float> ownDelayByNode = new IdentityHashMap<>();
        Map<Object, Float> totalDurationByNode = new IdentityHashMap<>();
        List<Object> durationNodes = new ArrayList<>();
        List<Boolean> expandedNodes = new ArrayList<>();
        durationNodes.add(timeline);
        expandedNodes.add(false);
        while (!durationNodes.isEmpty()) {
            int last = durationNodes.size() - 1;
            Object node = durationNodes.remove(last);
            boolean expanded = expandedNodes.remove(last);
            Field startField = null;
            Field childrenField = null;
            Field modeField = null;
            for (Class<?> current = node.getClass(); current != null;
                 current = current.getSuperclass()) {
                if (startField == null) {
                    try {
                        startField = current.getDeclaredField(START_DELAY_FIELD);
                        startField.trySetAccessible();
                    } catch (NoSuchFieldException ignored) {
                        // Continue through the animation hierarchy.
                    }
                }
                if (childrenField == null) {
                    try {
                        childrenField = current.getDeclaredField(CHILDREN_FIELD);
                        childrenField.trySetAccessible();
                    } catch (NoSuchFieldException ignored) {
                        // Leaf actions have no children.
                    }
                }
                if (modeField == null) {
                    try {
                        modeField = current.getDeclaredField("L50");
                        modeField.trySetAccessible();
                    } catch (NoSuchFieldException ignored) {
                        // Leaf actions have no timeline mode.
                    }
                }
            }
            float ownDelay = startField == null
                ? 0.0F : ((Number) startField.get(node)).floatValue();
            ownDelayByNode.put(node, ownDelay);
            Object childrenValue = childrenField == null ? null : childrenField.get(node);
            List<?> children = childrenValue instanceof List<?> list ? list : List.of();
            childrenByNode.put(node, children);
            int mode = modeField == null ? 0 : ((Number) modeField.get(node)).intValue();
            modeByNode.put(node, mode);

            if (!expanded && !children.isEmpty()) {
                durationNodes.add(node);
                expandedNodes.add(true);
                for (Object child : children) {
                    durationNodes.add(child);
                    expandedNodes.add(false);
                }
                continue;
            }

            if (children.isEmpty()) {
                Method totalDuration = null;
                for (Class<?> current = node.getClass(); current != null;
                     current = current.getSuperclass()) {
                    try {
                        totalDuration = current.getDeclaredMethod(TOTAL_DURATION_METHOD);
                        totalDuration.trySetAccessible();
                        break;
                    } catch (NoSuchMethodException ignored) {
                        // Continue through the action hierarchy.
                    }
                }
                float value = totalDuration == null
                    ? ownDelay : ((Number) totalDuration.invoke(node)).floatValue();
                totalDurationByNode.put(node, value);
            } else {
                float contentDuration = 0.0F;
                for (Object child : children) {
                    float childDuration = totalDurationByNode.getOrDefault(child, 0.0F);
                    contentDuration = mode == 2
                        ? Math.max(contentDuration, childDuration)
                        : contentDuration + childDuration;
                }
                totalDurationByNode.put(node, ownDelay + contentDuration);
            }
        }

        float spawnDelay = 0.0F;
        boolean foundSpawn = false;
        List<Object> pendingNodes = new ArrayList<>();
        List<Float> pendingOffsets = new ArrayList<>();
        pendingNodes.add(timeline);
        pendingOffsets.add(0.0F);
        while (!pendingNodes.isEmpty() && !foundSpawn) {
            int last = pendingNodes.size() - 1;
            Object node = pendingNodes.remove(last);
            float parentOffset = pendingOffsets.remove(last);

            Field callbackField = null;
            for (Class<?> current = node.getClass(); current != null;
                 current = current.getSuperclass()) {
                if (callbackField == null) {
                    try {
                        callbackField = current.getDeclaredField(CALLBACK_FIELD);
                        callbackField.trySetAccessible();
                    } catch (NoSuchFieldException ignored) {
                        // Timeline groups have no callback.
                    }
                }
            }
            float nodeOffset = parentOffset + ownDelayByNode.getOrDefault(node, 0.0F);

            Object callback = callbackField == null ? null : callbackField.get(node);
            if (callback != null) {
                for (Class<?> current = callback.getClass(); current != null && !foundSpawn;
                     current = current.getSuperclass()) {
                    for (Field field : current.getDeclaredFields()) {
                        if (field.getType() != String.class) continue;
                        field.trySetAccessible();
                        Object value = field.get(callback);
                        if ("spawn".equals(value) || "spawn_cherish".equals(value)) {
                            spawnDelay = nodeOffset;
                            foundSpawn = true;
                            break;
                        }
                    }
                }
            }

            List<?> childList = childrenByNode.getOrDefault(node, List.of());
            float sequenceOffset = 0.0F;
            for (Object child : childList) {
                pendingNodes.add(child);
                pendingOffsets.add(nodeOffset + sequenceOffset);
                if (modeByNode.getOrDefault(node, 0) != 2) {
                    sequenceOffset += totalDurationByNode.getOrDefault(child, 0.0F);
                }
            }
        }

        Object sound = null;
        for (Class<?> current = sendOut.getClass(); current != null;
             current = current.getSuperclass()) {
            try {
                Method soundMethod = current.getDeclaredMethod(SOUND_METHOD, short.class);
                soundMethod.trySetAccessible();
                sound = soundMethod.invoke(null, SOUND_ID);
                break;
            } catch (NoSuchMethodException ignored) {
                // Continue through the obfuscated client's class hierarchy.
            }
        }

        Object wrapper = createTimeline.invoke(null);
        beginGroup.invoke(wrapper);
        appendTimeline.invoke(wrapper, timeline);
        Object sparkleTrack = createTimeline.invoke(null);
        // The spawn callback releases the ball effect. The model's drop starts after the
        // following 0.4-second hold in TQ.Com8, so anchor the stars and sound to that boundary.
        delay.invoke(sparkleTrack, spawnDelay + DROP_START_DELAY_AFTER_SPAWN);
        beginGroup.invoke(sparkleTrack);
        appendEffect.invoke(sparkleTrack, sparkle);
        if (sound != null && appendEffect.getParameterTypes()[0].isInstance(sound)) {
            appendEffect.invoke(sparkleTrack, sound);
        }
        endGroup.invoke(sparkleTrack);
        appendTimeline.invoke(wrapper, sparkleTrack);
        endGroup.invoke(wrapper);
        timeline = wrapper;
    }
}
