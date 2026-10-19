package com.celllock.app;

/** 主机使用假 su，Android 使用固定只打印/退出的 shell 命令；不访问基带。 */
public final class RootShellTest {
    private static int starts() throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of("su-starts.txt");
        return java.nio.file.Files.exists(path) ? java.nio.file.Files.readAllLines(path).size() : 0;
    }
    private static void finish() throws Exception {
        java.lang.reflect.Field field = RootShell.class.getDeclaredField("shell");field.setAccessible(true);
        Process shell = (Process)field.get(null);
        java.lang.reflect.Method close = RootShell.class.getDeclaredMethod("closeShell");close.setAccessible(true);close.invoke(null);
        if (shell != null && !shell.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("test root shell did not exit");
        System.exit(0);
    }
    public static void main(String[] args) throws Exception {
        if (!"1".equals(System.getenv("CELLLOCK_ROOT_TEST"))) throw new AssertionError("test runner required");
        if (args[0].equals("auth-denied") || args[0].equals("retry-limit")) {
            int before = starts();boolean repeat = args[0].equals("retry-limit");
            if (repeat) RootShell.authorized();
            java.nio.file.Path reject = java.nio.file.Path.of(repeat ? "su-reject-always" : "su-reject-once");
            java.nio.file.Files.createFile(reject);boolean failed = false;
            try { RootShell.runDiagnostic("printf '{\"logs\":[]}'", 2); }
            catch (RootShell.Failure error) { failed = !error.started; }
            finally { java.nio.file.Files.deleteIfExists(reject); }
            if (!failed || starts() - before != (repeat ? 2 : 1) || RootShell.available()) throw new AssertionError("authorization retry limit or execution classification incorrect");
            if (!RootShell.runWrite("printf RECOVERED", 2).equals("RECOVERED")) throw new AssertionError("unstarted diagnostic poisoned subsequent writes");
            System.out.println("RootShell: " + args[0] + " passed");finish();
        }
        if (args[0].equals("read-retry") || args[0].equals("auth-read-retry") || args[0].equals("write-retry") || args[0].equals("write-once")) {
            int before = starts();RootShell.authorized();
            if (args[0].equals("read-retry") || args[0].equals("auth-read-retry")) {
                String failure = args[0].equals("read-retry") ? "exit 137" : "printf 'su: permission denied'; exit 1";
                String output = RootShell.run("if [ ! -f " + args[0] + " ]; then touch " + args[0] + "; " + failure + "; fi; printf READ_OK", 2);
                if (!output.equals("READ_OK") || starts() - before != 2) throw new AssertionError("read was not retried once");
            } else if (args[0].equals("write-retry")) {
                java.nio.file.Files.createFile(java.nio.file.Path.of("su-reject-once"));
                if (!RootShell.runWrite("printf WROTE >> write-attempt; printf WRITE_OK", 2).equals("WRITE_OK") || starts() - before != 2)
                    throw new AssertionError("unstarted write was not retried after authorization failed");
                if (!java.nio.file.Files.readString(java.nio.file.Path.of("write-attempt")).equals("WROTE") || !RootShell.available()) throw new AssertionError("write repeated or recovered authorization not remembered");
            } else {
                boolean failed = false;
                try { RootShell.runWrite("printf ONCE >> sent-write; exit 137", 2); } catch (RootShell.Failure error) { failed = error.started; }
                if (!failed || starts() - before != 1 || !java.nio.file.Files.readString(java.nio.file.Path.of("sent-write")).equals("ONCE"))
                    throw new AssertionError("started write was repeated or failure ignored");
            }
            System.out.println("RootShell: " + args[0] + " passed");finish();
        }
        if (args[0].equals("reuse")) {
            int before = starts();
            RootShell.authorized();
            for (int i = 0; i < 3; i++) {
                if (!RootShell.run("printf FAKE_WRITE_OK", 2).trim().equals("FAKE_WRITE_OK")) throw new AssertionError("output lost");
                RootShell.retry();
                if (!RootShell.available()) throw new AssertionError("manual refresh discarded working authorization");
            }
            if (!RootShell.run("exec printf EXEC_OK", 2).equals("EXEC_OK")) throw new AssertionError("exec killed shared shell");
            if (!RootShell.run("printf '{\"error\":\"library\"}'; exit 1", 2).contains("library")) throw new AssertionError("business failure lost");
            if (!RootShell.run("printf '中文\\nsecond'", 2).equals("中文\nsecond")) throw new AssertionError("output changed across commands");
            java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(3);
            try {
                java.util.List<java.util.concurrent.Future<String>> results = new java.util.ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    int value = i;
                    results.add(workers.submit(() -> RootShell.run("printf RESULT_" + value, 2)));
                }
                for (int i = 0; i < results.size(); i++) if (!results.get(i).get().equals("RESULT_" + i)) throw new AssertionError("concurrent command outputs mixed");
                java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Future<?> busy = workers.submit(() -> {
                    synchronized (RootShell.class) {
                        held.countDown();
                        try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    }
                });
                try {
                    if (!held.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("root command lock not acquired");
                    workers.submit(RootShell::retry).get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                } finally { release.countDown(); }
                busy.get();
            } finally { workers.shutdownNow(); }
            if (starts() - before != 1) throw new AssertionError("Root commands did not reuse one su session");
            System.out.println("RootShell: reuse passed");
            finish();
        }
        if (args[0].equals("reconnect") || args[0].equals("denied") || args[0].equals("overflow")) {
            int before = starts();
            RootShell.authorized();
            String command = args[0].equals("reconnect") ? "printf STARTED; sleep 3"
                    : args[0].equals("denied") ? "printf 'su: permission denied'; exit 1"
                    : "i=0; while [ $i -lt 28000 ]; do printf 0123456789; i=$((i+1)); done";
            boolean threw = false;
            try { RootShell.runWrite(command, args[0].equals("reconnect") ? 1 : 10); }
            catch (IllegalStateException error) { threw = true; }
            if (!threw || starts() - before != 1) throw new AssertionError("failure was ignored or automatically replayed");
            if (args[0].equals("denied")) {
                if (RootShell.available()) throw new AssertionError("denied root remained authorized");
                RootShell.retry();
            }
            if (!RootShell.run("printf NEXT_OK", 2).equals("NEXT_OK") || starts() - before != 2) throw new AssertionError("failed shell was not replaced on next command");
            if (args[0].equals("reconnect")) {
                java.lang.reflect.Field field = RootShell.class.getDeclaredField("shell");field.setAccessible(true);
                Process shell = (Process)field.get(null);shell.destroyForcibly();shell.waitFor();
                if (!RootShell.run("printf AFTER_EXIT", 2).equals("AFTER_EXIT") || starts() - before != 3) throw new AssertionError("dead session was reused");
            }
            System.out.println("RootShell: " + args[0] + " passed");
            finish();
        }
        boolean failed = args[0].equals("watchdog") || args[0].equals("signal")
                || args[0].equals("incomplete") || args[0].equals("missing");
        boolean unsafe = failed || args[0].equals("cleanup");
        RootShell.authorized();
        boolean threw = false;
        String diagnostic = switch (args[0]) {
            case "logs" -> "printf '{\"logs\":[]}\\n'";
            case "watchdog" -> "printf 'vendor warning before watchdog exit'; exit 124";
            case "signal" -> "printf 'vendor warning before signal exit'; exit 137";
            case "incomplete" -> "printf '{\"incomplete\":true}'";
            case "missing" -> "printf 'vendor warning: missing completion'; exit 1";
            default -> "printf '{\"error\":\"" + args[0] + "\"}'; exit 1";
        };
        try {
            String output = RootShell.runDiagnostic(args.length > 1 ? args[1] : diagnostic, 2);
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
            if (!RootShell.runWrite(args.length > 2 ? args[2] : "printf FAKE_WRITE_OK", 2).trim().equals("FAKE_WRITE_OK")) throw new AssertionError("fake write missing");
        } catch (IllegalStateException error) { threw = true; }
        if (threw != unsafe) throw new AssertionError("incorrect write protection: " + args[0]);
        threw = false;
        try { RootShell.runBackend("printf READ_ONLY", 2, true); } catch (IllegalStateException error) { threw = true; }
        if (threw != unsafe) throw new AssertionError("state read bypassed diagnostic protection: " + args[0]);
        System.out.println("RootShell: " + args[0] + " passed");
        finish();
    }
}
