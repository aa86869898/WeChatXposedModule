package com.leshao.v3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import de.robv.android.xposed.XposedHelpers;

/**
 * 反射工具类 — 安全封装 XposedHelpers
 * 所有方法在找不到时返回 null 而不抛异常
 */
public class ReflectUtil {

    /**
     * 安全查找类，找不到返回 null
     */
    public static Class<?> findClassSafe(String className, ClassLoader cl) {
        try {
            return XposedHelpers.findClass(className, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 安全获取对象字段值
     */
    public static Object getFieldSafe(Object obj, String fieldName) {
        try {
            return XposedHelpers.getObjectField(obj, fieldName);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 安全获取静态字段
     */
    public static Object getStaticFieldSafe(Class<?> clazz, String fieldName) {
        try {
            return XposedHelpers.getStaticObjectField(clazz, fieldName);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 安全设置字段
     */
    public static void setFieldSafe(Object obj, String fieldName, Object value) {
        try {
            XposedHelpers.setObjectField(obj, fieldName, value);
        } catch (Throwable t) {}
    }

    /**
     * 安全调用方法
     */
    public static Object callMethodSafe(Object obj, String methodName, Object... args) {
        try {
            return XposedHelpers.callMethod(obj, methodName, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 安全调用静态方法
     */
    public static Object callStaticMethodSafe(Class<?> clazz, String methodName, Object... args) {
        try {
            return XposedHelpers.callStaticMethod(clazz, methodName, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 获取所有字段（包括父类）
     */
    public static void listFields(Object obj) {
        if (obj == null) return;
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                f.setAccessible(true);
                try {
                    Logger.i("  Field: " + f.getType().getSimpleName() + " " + f.getName()
                            + " = " + f.get(obj));
                } catch (Exception e) {}
            }
            clazz = clazz.getSuperclass();
        }
    }
}
