package com.cloud360.pos.util;

import com.cloud360.pos.model.User;

public final class Session {

    private static User currentUser;

    private Session() {}

    public static void login(User user) { currentUser = user; }

    public static void logout() { currentUser = null; }

    public static User getUser() { return currentUser; }

    public static boolean isLoggedIn() { return currentUser != null; }
}