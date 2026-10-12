package gg.hcfrevival.revival;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Marks a RevivalPaper test so {@link RevivalTestSuite} (Gradle task {@code runRevivalTests}) runs it.
 * Plain unit tests need nothing else; tests that need registries add Paper's test extensions.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Tag("RevivalTest")
public @interface RevivalTest {
}
