package io.quartetqe.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/** Runs one plain-English test once per device of a screen target, on web, mobile web and native. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@TestTemplate
@ExtendWith(SurfaceInvocations.class)
public @interface OnEverySurface {
    String value();

    /** Limits the test to device kinds such as "web", for checks other surfaces cannot make. */
    String[] kinds() default {};
}
