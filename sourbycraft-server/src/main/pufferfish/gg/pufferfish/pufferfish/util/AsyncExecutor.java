package gg.pufferfish.pufferfish.util;

import com.google.common.collect.Queues;
import gg.pufferfish.pufferfish.PufferfishLogger;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

public class AsyncExecutor implements Runnable {

    private final Queue<Runnable> jobs = Queues.newArrayDeque();
    private final Lock mutex = new ReentrantLock();
    private final Condition cond = mutex.newCondition();
    private final Thread thread;
    private volatile boolean killswitch = false;

    public AsyncExecutor(String threadName) {
        this.thread = new Thread(this, threadName);
    }

    public void start() {
        thread.start();
    }

    public void kill() {
        killswitch = true;
        cond.signalAll();
    }

    public void submit(Runnable runnable) {
        mutex.lock();
        try {
            jobs.offer(runnable);
            cond.signalAll();
        } finally {
            mutex.unlock();
        }
    }

    private static final java.util.List<AsyncExecutor> POOL = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static int getPoolSize() {
        return POOL.size();
    }

    public static void initPool(int threadCount) {
        shutdownPool();
        for (int i = 0; i < threadCount; i++) {
            AsyncExecutor exec = new AsyncExecutor("SourbyCraft Async Worker #" + i);
            exec.start();
            POOL.add(exec);
        }
    }

    public static void shutdownPool() {
        for (AsyncExecutor exec : POOL) {
            exec.kill();
        }
        POOL.clear();
    }

    public static void submitToPool(Runnable task) {
        if (POOL.isEmpty()) {
            task.run();
            return;
        }
        POOL.get((int) (Math.random() * POOL.size())).submit(task);
    }

    @Override
    public void run() {
        while (!killswitch) {
            try {
                Runnable runnable = takeRunnable();
                if (runnable != null) {
                    runnable.run();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                PufferfishLogger.LOGGER.log(Level.SEVERE, e, () -> "Failed to execute async job for thread " + thread.getName());
            }
        }
    }

    private Runnable takeRunnable() throws InterruptedException {
        mutex.lock();
        try {
            while (jobs.isEmpty() && !killswitch) {
                cond.await();
            }

            if (jobs.isEmpty()) return null; // We've set killswitch

            return jobs.remove();
        } finally {
            mutex.unlock();
        }
    }

}
