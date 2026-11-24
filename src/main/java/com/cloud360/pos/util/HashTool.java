package com.cloud360.pos.util;

import org.mindrot.jbcrypt.BCrypt;

/**
 * Prints a BCrypt hash for a password, so you can add staff with SQL until the Staff screen exists.
 * Run:  java -cp target/pos-1.0.0.jar com.cloud360.pos.util.HashTool "MyPassword"
 * (In IntelliJ: Run > Edit Configurations > Program arguments.)
 */
public final class HashTool {

    private HashTool() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: HashTool <password>");
            return;
        }
        System.out.println(BCrypt.hashpw(args[0], BCrypt.gensalt(10)));
    }
}