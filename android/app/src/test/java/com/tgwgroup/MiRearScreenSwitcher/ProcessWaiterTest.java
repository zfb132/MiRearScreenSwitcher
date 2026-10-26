package com.tgwgroup.MiRearScreenSwitcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.junit.Test;

public class ProcessWaiterTest {
    @Test
    public void returnsTheExitCodeOfAnAlreadyCompletedProcess() throws Exception {
        assertEquals(17, ProcessWaiter.awaitExit(new FakeProcess(false), 0));
    }

    @Test
    public void boundsAnUnresponsiveAuthorizationProcess() {
        assertThrows(IOException.class, () -> ProcessWaiter.awaitExit(new FakeProcess(true), 0));
    }

    @Test
    public void allowsTheProbeWorkerToBeInterrupted() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class,
                    () -> ProcessWaiter.awaitExit(new FakeProcess(true), 30_000));
        } finally {
            Thread.interrupted();
        }
    }

    private static final class FakeProcess extends Process {
        private boolean running;

        FakeProcess(boolean running) {
            this.running = running;
        }

        @Override public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override public int waitFor() {
            return exitValue();
        }

        @Override public int exitValue() {
            if (running) throw new IllegalThreadStateException();
            return 17;
        }

        @Override public void destroy() {
            running = false;
        }
    }
}
