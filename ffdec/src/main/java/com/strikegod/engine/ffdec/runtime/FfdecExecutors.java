package com.strikegod.engine.ffdec.runtime;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Java 25 execution helpers for the embedded FFDEC source tree.
 *
 * <p>The ffdec module intentionally stays independent from Spring/infra to avoid a module cycle. Engine-level activity
 * batching is already gated by SharedVirtualExecutor; these executors only replace FFDEC's short-lived internal
 * platform thread pools.
 */
public final class FfdecExecutors {

    private static final Logger LOGGER = Logger.getLogger(FfdecExecutors.class.getName());
    private static final AtomicInteger POOL_SEQUENCE = new AtomicInteger();

    /**
     * 可选注入的"共享治理 executor"。注入后所有 {@link #newVirtualTaskExecutor} 和
     * {@link #newBoundedVirtualTaskExecutor} 都会把任务委托给它（典型来源：
     * SharedVirtualExecutor.asGatedAwareExecutor）。未注入时走 ffdec 自家虚拟线程工厂，
     * 保持 ffdec 模块独立可用 —— 不引入对 infra 的依赖。
     */
    private static volatile Executor sharedAware = null;

    private FfdecExecutors() {}

    /**
     * 注入共享治理 executor。线程安全：volatile 写发布，后续 newXxxTaskExecutor 调用看到新值。
     * 已存在的 executor 实例不会被改写（它们已经持有创建时的 delegate）。
     */
    public static void setSharedAwareExecutor(Executor executor) {
        sharedAware = Objects.requireNonNull(executor, "shared-aware executor 不能为空");
    }

    public static ExecutorService newVirtualTaskExecutor(String purpose) {
        Executor share = sharedAware;
        if (share != null) {
            return new SharedAwareExecutorService(share, threadNamePrefix(purpose));
        }
        ThreadFactory factory =
                Thread.ofVirtual().name(threadNamePrefix(purpose) + "-", 0).factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }

    public static ExecutorService newBoundedVirtualTaskExecutor(String purpose, int maxParallelism) {
        return new BoundedVirtualExecutorService(purpose, newVirtualTaskExecutor(purpose), Math.max(1, maxParallelism));
    }

    public static Thread newPlatformShutdownHook(String purpose, Runnable command) {
        return Thread.ofPlatform().name(threadNamePrefix(purpose) + "-shutdown").unstarted(command);
    }

    public static Thread startPlatformDaemon(String purpose, int priority, Runnable command) {
        Thread thread =
                Thread.ofPlatform().name(threadNamePrefix(purpose)).daemon(true).unstarted(command);
        thread.setPriority(priority);
        thread.start();
        return thread;
    }

    public static Thread startVirtualThread(String purpose, Runnable command) {
        return Thread.ofVirtual().name(threadNamePrefix(purpose)).start(command);
    }

    public static void shutdownNowAfter(ExecutorService executor, Duration timeout, Runnable onTimeout) {
        executor.shutdown();
        boolean terminated = false;
        boolean interrupted = false;
        try {
            terminated = executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            interrupted = true;
        }
        if (!terminated) {
            if (!interrupted && onTimeout != null) {
                onTimeout.run();
            }
            executor.shutdownNow();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public static String describe(ExecutorService executor) {
        if (executor instanceof BoundedVirtualExecutorService bounded) {
            return bounded.describe();
        }
        return "executor: " + executor.getClass().getSimpleName()
                + " shutdown: " + executor.isShutdown()
                + " terminated: " + executor.isTerminated();
    }

    private static String threadNamePrefix(String purpose) {
        String safePurpose = purpose == null || purpose.isBlank() ? "task" : purpose.replaceAll("[^a-zA-Z0-9._-]", "-");
        return "ffdec-" + safePurpose + "-" + POOL_SEQUENCE.incrementAndGet();
    }

    private static final class BoundedVirtualExecutorService extends AbstractExecutorService {

        private final String purpose;
        private final ExecutorService delegate;
        private final Semaphore gate;
        private final int maxParallelism;
        private final LongAdder submitted = new LongAdder();
        private final LongAdder started = new LongAdder();
        private final LongAdder completed = new LongAdder();
        private final LongAdder interruptedBeforeStart = new LongAdder();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger largestActive = new AtomicInteger();

        private BoundedVirtualExecutorService(String purpose, ExecutorService delegate, int maxParallelism) {
            this.purpose = purpose == null || purpose.isBlank() ? "task" : purpose;
            this.delegate = delegate;
            this.maxParallelism = maxParallelism;
            this.gate = new Semaphore(maxParallelism, true);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public void execute(Runnable command) {
            submitted.increment();
            delegate.execute(() -> {
                boolean acquired = false;
                try {
                    gate.acquire();
                    acquired = true;
                    started.increment();
                    int nowActive = active.incrementAndGet();
                    largestActive.accumulateAndGet(nowActive, Math::max);
                    command.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    interruptedBeforeStart.increment();
                    if (command instanceof java.util.concurrent.Future<?> future) {
                        future.cancel(true);
                    } else {
                        LOGGER.log(Level.FINE, "Interrupted before starting FFDEC task for {0}", purpose);
                    }
                } finally {
                    if (acquired) {
                        active.decrementAndGet();
                        completed.increment();
                    }
                    if (acquired) {
                        gate.release();
                    }
                }
            });
        }

        private String describe() {
            return "parallelism: " + maxParallelism
                    + " active: " + active.get()
                    + " largest: " + largestActive.get()
                    + " submitted: " + submitted.sum()
                    + " started: " + started.sum()
                    + " completed: " + completed.sum()
                    + " interrupted-before-start: " + interruptedBeforeStart.sum()
                    + " shutdown: " + delegate.isShutdown()
                    + " terminated: " + delegate.isTerminated();
        }
    }

    /**
     * 把外部注入的 {@link Executor}（典型来自 SharedVirtualExecutor.asGatedAwareExecutor）包装成
     * {@link ExecutorService}：
     *
     * <ul>
     *   <li>execute：本地 active 计数 +1，转发到 delegate；任务跑完 finally 计数 -1 并 notify 等待者。</li>
     *   <li>shutdown：仅置标志，不真销毁 delegate（delegate 是共享池，生命周期由它的所有者管）。</li>
     *   <li>shutdownNow：仅置标志 + 返回空 list（不中断共享池线程，那会影响其他模块）。</li>
     *   <li>awaitTermination：在内部 monitor 上 wait 直到 shutdown 且 active 归零，或者超时。
     *       这条对 AS2/AS3 ScriptExporter 这种 "submit-N、shutdownNowAfter(timeout) 阻塞、再读
     *       future.isDone()" 模式至关重要 —— 不真等待会丢失未完成 future 的结果。</li>
     * </ul>
     */
    private static final class SharedAwareExecutorService extends AbstractExecutorService {

        private final Executor delegate;
        private final String purpose;
        private final Object terminationLock = new Object();
        private final AtomicInteger activeCount = new AtomicInteger();
        private volatile boolean shutdown = false;

        SharedAwareExecutorService(Executor delegate, String purpose) {
            this.delegate = delegate;
            this.purpose = purpose;
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new RejectedExecutionException("SharedAwareExecutorService(" + purpose + ") already shut down");
            }
            activeCount.incrementAndGet();
            try {
                delegate.execute(() -> {
                    try {
                        command.run();
                    } finally {
                        onTaskDone();
                    }
                });
            } catch (RuntimeException ex) {
                // delegate.execute 抛异常（罕见，如 RejectedExecutionException），归还计数避免泄漏
                onTaskDone();
                throw ex;
            }
        }

        private void onTaskDone() {
            if (activeCount.decrementAndGet() == 0 && shutdown) {
                synchronized (terminationLock) {
                    terminationLock.notifyAll();
                }
            }
        }

        @Override
        public void shutdown() {
            shutdown = true;
            if (activeCount.get() == 0) {
                synchronized (terminationLock) {
                    terminationLock.notifyAll();
                }
            }
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown();
            // 共享池上正在跑的任务不能强中断（会影响其他模块）；返回空 list 表示"已下发停机意图、未抢回任何任务"。
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && activeCount.get() == 0;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            long deadlineNs = System.nanoTime() + unit.toNanos(timeout);
            synchronized (terminationLock) {
                while (!isTerminated()) {
                    long remainingNs = deadlineNs - System.nanoTime();
                    if (remainingNs <= 0L) {
                        return false;
                    }
                    TimeUnit.NANOSECONDS.timedWait(terminationLock, remainingNs);
                }
            }
            return true;
        }
    }
}
