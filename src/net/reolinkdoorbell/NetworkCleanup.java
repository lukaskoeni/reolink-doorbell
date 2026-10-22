// SPDX-License-Identifier: Apache-2.0
package net.reolinkdoorbell;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** TLS close/disconnect can write to the network, even when only cancelling. */
final class NetworkCleanup {
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "reolink-network-cleanup"); t.setDaemon(true); return t;
    });
    interface Action { void run() throws Exception; }
    static final class Task implements AutoCloseable {
        private final Action action;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final CountDownLatch finished = new CountDownLatch(1);
        Task(Action action) { this.action = action; }
        public void close() { if (claimed.compareAndSet(false, true)) WORKERS.execute(this::perform); }
        void closeOnOwnerThread() { if (claimed.compareAndSet(false, true)) perform(); }
        private void perform() {
            try { action.run(); }
            catch (Exception ignored) {}
            finally { finished.countDown(); }
        }
        boolean await(long timeout, TimeUnit unit) throws InterruptedException { return finished.await(timeout, unit); }
    }
    private NetworkCleanup() {}
}
