package com.valui.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method for automatic audit logging.
 * {@code AuditAspect} intercepts calls and publishes an {@code AuditEvent}
 * to the {@code audit.log} Kafka topic both on success and on error.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audit {

    /** Domain action name, e.g. "ADD_CONTROLLER", "BAN_USER". */
    String action();

    /** Optional entity type being operated on, e.g. "Controller", "User". */
    String entityType() default "";
}
