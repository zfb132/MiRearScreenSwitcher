package com.tgwgroup.MiRearScreenSwitcher;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Bounded process waiting compatible with the app's Android 7 / API 24 minimum. */
final class ProcessWaiter {
    private ProcessWaiter() {}

    static int awaitExit(Process process, long timeoutMillis)
            throws IOException, InterruptedException {
        long started = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException running) {
                if (System.nanoTime() - started >= timeoutNanos) {
                    throw new IOException("AxManager authorization timed out");
                }
                Thread.sleep(100);
            }
        }
    }
}
