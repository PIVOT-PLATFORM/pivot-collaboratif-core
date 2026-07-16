package fr.pivot.collaboratif.whiteboard.canvas;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Provides a small, bounded thread pool dedicated to off-thread persistence of {@code DRAW}
 * canvas events (see {@link CanvasEventWriter}). {@code @EnableAsync} is already switched on
 * app-wide by {@code OpenGraphAsyncConfig}, so this class only contributes the executor bean.
 *
 * <p>Kept separate from the OpenGraph pool on purpose: DRAW is the highest-frequency canvas
 * mutation, and a burst of drawing must not queue behind (or starve) link-preview enrichment,
 * nor vice-versa. Same bounded-pool rationale as OpenGraph: a bounded queue delays persistence
 * under load (still non-blocking for the STOMP handler thread) rather than spawning unbounded
 * threads. The events written here are not read on any production path today (replay-on-join is
 * unwired), so a saturated queue that sheds nothing user-visible is an acceptable failure mode.
 */
@Configuration
class CanvasEventAsyncConfig {

    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 500;

    /**
     * The executor referenced by {@code @Async("canvasEventExecutor")} on {@link CanvasEventWriter}.
     *
     * @return a bounded {@link ThreadPoolTaskExecutor}
     */
    @Bean("canvasEventExecutor")
    TaskExecutor canvasEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("canvas-evt-");
        // Shed silently when saturated instead of the default AbortPolicy. Spring submits a
        // void @Async task on the CALLING (STOMP handler) thread, so AbortPolicy would surface a
        // TaskRejectedException on the hot path when a slow DB fills the queue — exactly the
        // user-visible failure this off-thread writer exists to avoid. These DRAW rows are read on
        // no production path (replay-on-join unwired), so discarding the overflow is the documented,
        // acceptable failure mode.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
