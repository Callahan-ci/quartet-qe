package io.quartetqe.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The acceptance criteria a test proves, for example {@code @AcceptanceCriteria("AC1")}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AcceptanceCriteria {
    String[] value();
}
