package com.valui.admin.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/** Static helpers for accessing the current user from anywhere (services, aspects, etc.). */
public final class CurrentUserUtil {

    private CurrentUserUtil() {}

    public static ValuiPrincipal getPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof ValuiPrincipal principal)) {
            throw new IllegalStateException("No authenticated ValuiPrincipal in SecurityContext");
        }
        return principal;
    }

    public static UUID getCurrentUserId()      { return getPrincipal().userId(); }
    public static Long getCurrentTelegramId()  { return getPrincipal().telegramId(); }
    public static String getCurrentRole()      { return getPrincipal().role(); }
    public static String getCurrentPlan()      { return getPrincipal().plan(); }
}
