package com.celllock.app;

/** 主机使用假 su，Android 使用固定只打印/退出的 shell 命令；不访问基带。 */
public final class RootShellTest {
    public static void main(String[] args) throws Exception {
        if (!"1".equals(System.getenv("CELLLOCK_ROOT_TEST"))) throw new AssertionError("test runner required");
        boolean failed = args[0].equals("watchdog") || args[0].equals("signal")
                || args[0].equals("incomplete") || args[0].equals("missing");
        boolean unsafe = failed || args[0].equals("cleanup");
        RootShell.authorized();
        boolean threw = false;
        try {
            String output = RootShell.runDiagnostic(args.length > 1 ? args[1] : args[0], 2);
            if (!output.contains(args[0])) throw new AssertionError("diagnostic output lost");
        } catch (IllegalStateException error) {
            if (!failed) throw new AssertionError("unexpected diagnostic failure: " + args[0], error);
            threw = true;
        }
        if (threw != failed) throw new AssertionError("incorrect diagnostic exit handling: " + args[0]);
        RootShell.retry();
        RootShell.authorized();
        threw = false;
        try {
            if (!RootShell.runWrite(args.length > 2 ? args[2] : "write", 2).trim().equals("FAKE_WRITE_OK")) throw new AssertionError("fake write missing");
        } catch (IllegalStateException error) { threw = true; }
        if (threw != unsafe) throw new AssertionError("incorrect write protection: " + args[0]);
        System.out.println("RootShell: " + args[0] + " passed");
        System.exit(0);
    }
}
