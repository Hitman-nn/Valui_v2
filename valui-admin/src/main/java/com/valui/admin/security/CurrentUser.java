package com.valui.admin.security;

import java.lang.annotation.*;

/**
 * Injects the authenticated {@link ValuiPrincipal} as a controller method parameter.
 *
 * <pre>
 * {@code @GetMapping("/me")
 * public UserDto getMe(@CurrentUser ValuiPrincipal principal) { ... }}
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {}
