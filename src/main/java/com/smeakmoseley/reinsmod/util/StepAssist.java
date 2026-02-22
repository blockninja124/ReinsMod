package com.smeakmoseley.reinsmod.util;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class StepAssist {
    private static final boolean DEBUG = false;

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile boolean RESOLVED = false;

    // Try method first (newer mappings)
    private static Method SET_MAX_UP_STEP = null;
    private static Method GET_MAX_UP_STEP = null;

    // Fallback field (older mappings / remaps)
    private static Field MAX_UP_STEP_FIELD = null;

    private StepAssist() {}

    private static void resolve(Entity e) {
        if (RESOLVED) return;
        RESOLVED = true;

        Class<?> c = e.getClass();
        // Walk up class chain to Entity
        while (c != null) {
            // Try common method names
            try { SET_MAX_UP_STEP = c.getMethod("setMaxUpStep", float.class); SET_MAX_UP_STEP.setAccessible(true); } catch (Throwable ignored) {}
            try { GET_MAX_UP_STEP = c.getMethod("getMaxUpStep"); GET_MAX_UP_STEP.setAccessible(true); } catch (Throwable ignored) {}

            // Try common field names
            try { MAX_UP_STEP_FIELD = c.getDeclaredField("maxUpStep"); MAX_UP_STEP_FIELD.setAccessible(true); } catch (Throwable ignored) {}
            try { if (MAX_UP_STEP_FIELD == null) { MAX_UP_STEP_FIELD = c.getDeclaredField("stepHeight"); MAX_UP_STEP_FIELD.setAccessible(true); } } catch (Throwable ignored) {}

            if (SET_MAX_UP_STEP != null || MAX_UP_STEP_FIELD != null) break;
            c = c.getSuperclass();
        }

        if (DEBUG) {
            LOGGER.info("[ReinsMod] StepAssist resolved: setMethod={} getMethod={} field={}",
                    (SET_MAX_UP_STEP != null),
                    (GET_MAX_UP_STEP != null),
                    (MAX_UP_STEP_FIELD != null ? MAX_UP_STEP_FIELD.getName() : "null")
            );
        }
    }

    /** Returns current step value, or -1 if unknown */
    public static float getStep(Entity e) {
        if (e == null) return -1f;
        resolve(e);
        try {
            if (GET_MAX_UP_STEP != null) {
                Object v = GET_MAX_UP_STEP.invoke(e);
                if (v instanceof Number n) return n.floatValue();
            }
            if (MAX_UP_STEP_FIELD != null) {
                Object v = MAX_UP_STEP_FIELD.get(e);
                if (v instanceof Number n) return n.floatValue();
            }
        } catch (Throwable ignored) {}
        return -1f;
    }

    public static void setStep(Entity e, float step) {
        if (e == null) return;
        resolve(e);
        try {
            if (SET_MAX_UP_STEP != null) {
                SET_MAX_UP_STEP.invoke(e, step);
                return;
            }
            if (MAX_UP_STEP_FIELD != null) {
                MAX_UP_STEP_FIELD.set(e, step);
            }
        } catch (Throwable ignored) {}
    }
}
