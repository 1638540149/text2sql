package com.text2sql.util;

import com.text2sql.security.LoginUser;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {
    private CurrentUser() {}

    public static LoginUser get() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return (LoginUser) principal;
    }
}
