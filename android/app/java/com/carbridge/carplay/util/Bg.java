package com.carbridge.carplay.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 命名线程池：项目里「起一条后台线程」的地方统一从这里走。
 *
 * <p>为什么不再直接 {@code new Thread(...).start()}：
 * <ul>
 *   <li><b>可定位</b>——线程名统一带 {@code carbridge-} 前缀且语义化，
 *       看门狗 dump 线程栈、看 logcat、抓诊断包时一眼能对上「这是哪条链路」；</li>
 *   <li><b>不静默死</b>——后台线程抛出的异常没人接，线程直接消失，
 *       现场表现就是「某个功能突然不动了、日志什么都没有」。
 *       这里统一兜住并交给 {@link Diag}，至少留下一条可查的痕迹；</li>
 *   <li><b>少建线程</b>——会话反复重连时线程被池子复用，不用每次重新创建。</li>
 * </ul>
 *
 * <p>线程名形如 {@code carbridge-ap-event}：前缀固定，后面是调用方给的语义名。
 */
public final class Bg {

    /** 线程名统一前缀：{@code dumpStuckThreads()} 之类的排查代码按它筛线程。 */
    public static final String PREFIX = "carbridge-";

    private static final AtomicInteger SEQ = new AtomicInteger();

    /**
     * 池是懒建的、且不是 final：{@link #shutdown()} 之后还能重新起来。
     * 本项目会在「重连 / 换车机」时反复重建会话，如果清一次线程就永久不可用，
     * 后面所有 Bg.run 都会变成静默丢弃，比不清更糟。
     */
    private static ExecutorService pool;

    private Bg() {
    }

    /** 取（必要时创建）单例池。 */
    private static synchronized ExecutorService pool() {
        if (pool == null || pool.isShutdown()) {
            pool = Executors.newCachedThreadPool(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    // 兜底名字：任务还没开始（或刚结束）时线程上没有语义名，先给个能认出来的
                    Thread t = new Thread(r, PREFIX + "worker-" + SEQ.incrementAndGet());
                    // ⚠️ 必须 daemon：池线程空闲/卡在阻塞读上时会长期存活，
                    //    非 daemon 会吊住进程退出。Android 上进程由系统回收，daemon 不影响功能。
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        return pool;
    }

    /**
     * 后台执行 {@code r}，线程名为 {@code carbridge-<name>}。
     *
     * <p>{@code name} 用英文短横线语义名（如 {@code ap-event}、{@code wtx-iap2-read}），
     * 排查时靠它区分线程；传 null 也不炸，只是名字退化成 {@code carbridge-bg}。
     */
    public static void run(final String name, final Runnable r) {
        if (r == null) return;
        final String tag = (name == null || name.length() == 0) ? "bg" : name;
        try {
            pool().execute(new Runnable() {
                @Override
                public void run() {
                    // 池线程会被复用，名字只能在真正开跑时改成当前任务的，
                    // 否则同一个线程前后跑两个任务，日志里永远是第一个名字。
                    Thread self = Thread.currentThread();
                    String old = self.getName();
                    if (!old.equals(PREFIX + tag)) self.setName(PREFIX + tag);
                    try {
                        r.run();
                    } catch (Throwable t) {
                        // 后台线程的异常没有调用方可以抛，只能就地记下来
                        Diag.w(tag, t);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // 池正在/已经被关掉。调用点多半在状态机收尾路径上，
            // 把异常甩回去会打断收尾流程，所以只记录。
            Diag.w(tag, e);
        } catch (Throwable t) {
            Diag.w(tag, t);
        }
    }

    /**
     * 关掉池（进程退出、或主动清线程时用）。
     *
     * <p>直接 {@code shutdownNow()} 不等待：池里跑的都是长驻循环，
     * 靠中断收不干净，真正的资源释放仍然由各会话自己的 {@code stop()} 负责，
     * 这里只是把线程标记成可回收，避免主线程被阻塞。
     */
    public static synchronized void shutdown() {
        ExecutorService p = pool;
        pool = null;
        if (p == null) return;
        try {
            p.shutdownNow();
        } catch (Throwable t) {
            Diag.w("bg-shutdown", t);
        }
    }
}
