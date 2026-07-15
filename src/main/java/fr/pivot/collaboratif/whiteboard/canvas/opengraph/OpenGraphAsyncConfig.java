package fr.pivot.collaboratif.whiteboard.canvas.opengraph;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Enables {@code @Async} and provides a small, bounded thread pool dedicated to OpenGraph
 * enrichment (US08.6.5) — kept in its own {@code @Configuration} class (rather than added to
 * {@code PivotCollaboratifApplication}, a file every other Sprint 12 card-type agent might also
 * be tempted to touch) precisely to avoid a shared-file merge conflict.
 *
 * <p>Deliberately not the JVM-wide default {@code SimpleAsyncTaskExecutor} (unbounded thread
 * creation, one thread per task) — a bounded pool with a bounded queue means a burst of card
 * creations/updates queues enrichment work instead of spawning unbounded threads, and a
 * saturated queue simply delays enrichment (still non-blocking for the STOMP handler thread)
 * rather than exhausting server resources.
 */
@Configuration
@EnableAsync
class OpenGraphAsyncConfig {

    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 100;

    /**
     * The executor referenced by {@code @Async("openGraphExecutor")} on {@link
     * OpenGraphEnrichmentListener}.
     *
     * @return a bounded {@link ThreadPoolTaskExecutor}
     */
    @Bean("openGraphExecutor")
    TaskExecutor openGraphExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("og-enrich-");
        executor.initialize();
        return executor;
    }
}
