package com.siberanka.twiantivpn.core.asteroid;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

public final class AsteroidRegistryHook {
    private static final String[] REGISTRY_CLASSES = new String[]{
            "me.serbob.asteroidapi.registries.FakePlayerRegistry",
            "me.serbob.asteroidapi.Registries.FakePlayerRegistry"
    };

    private AsteroidRegistryHook() {
    }

    public static boolean isFakePlayer(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        for (String className : REGISTRY_CLASSES) {
            if (isFakePlayer(className, uuid)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFakePlayer(String className, UUID uuid) {
        try {
            Class<?> registryClass = Class.forName(className);
            Object registry = getRegistryInstance(registryClass);

            try {
                Method method = registryClass.getMethod("isAFakePlayer", UUID.class);
                Object result = method.invoke(registry, uuid);
                if (result instanceof Boolean && (Boolean) result) {
                    return true;
                }
            } catch (NoSuchMethodException ignored) {
            }

            try {
                Method method = registryClass.getMethod("getFakePlayers");
                Object result = method.invoke(registry);
                if (result instanceof Map) {
                    return ((Map<?, ?>) result).containsKey(uuid);
                }
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Object getRegistryInstance(Class<?> registryClass) throws ReflectiveOperationException {
        try {
            Field instance = registryClass.getField("INSTANCE");
            Object value = instance.get(null);
            if (value != null) {
                return value;
            }
        } catch (NoSuchFieldException ignored) {
        }
        return registryClass.getConstructor().newInstance();
    }
}
