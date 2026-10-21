package dev.iyanz.sourbycraft.api;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UnsupportedTest {
    @Unsupported(reason = "kept for the test")
    static class WithReason {
        @Unsupported
        void bare() {
        }

        @Unsupported(reason = "ctor reason")
        WithReason() {
        }
    }

    @Test
    void isVisibleAtRuntimeSoToolsAndPluginsCanReadIt() {
        assertEquals(RetentionPolicy.RUNTIME, Unsupported.class.getAnnotation(Retention.class).value());
        assertNotNull(Unsupported.class.getAnnotation(Documented.class));
    }

    @Test
    void targetsAreExactlyTypesMethodsAndConstructors() {
        assertEquals(Set.of(ElementType.METHOD, ElementType.TYPE, ElementType.CONSTRUCTOR),
            Set.copyOf(java.util.List.of(Unsupported.class.getAnnotation(Target.class).value())));
    }

    @Test
    void reasonDefaultsToEmptyAndIsReadableOnTypesMethodsAndConstructors() throws NoSuchMethodException {
        assertEquals("kept for the test", WithReason.class.getAnnotation(Unsupported.class).reason());
        final Method bare = WithReason.class.getDeclaredMethod("bare");
        assertEquals("", bare.getAnnotation(Unsupported.class).reason());
        assertEquals("ctor reason", WithReason.class.getDeclaredConstructor().getAnnotation(Unsupported.class).reason());
    }

    @Test
    void theOnlyMemberIsReason() {
        assertEquals(1, Unsupported.class.getDeclaredMethods().length);
        assertEquals(String.class, Unsupported.class.getDeclaredMethods()[0].getReturnType());
        assertEquals("reason", Unsupported.class.getDeclaredMethods()[0].getName());
    }
}
