package dev.s1mp1e.worldconv;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Machine-readable lines for the launcher. Captured before the game's bootstrap, which wraps System.out into its
 * logger (every line would come out as "[main/INFO]: [STDOUT]: ...").
 */
final class Out {
    private static PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

    private Out() {}

    static void capture() {
        out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
    }

    static synchronized void line(String s) {
        out.println(s);
    }
}
