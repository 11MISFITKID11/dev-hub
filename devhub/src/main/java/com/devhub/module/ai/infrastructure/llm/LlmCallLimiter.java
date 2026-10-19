package com.devhub.module.ai.infrastructure.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * LLM 调用并发限制器（高并发防护的核心组件）
 * <p>
 * LLM API 有供应商侧并发配额，无限制放行会在高并发下触发 429 连锁
 * （熔断器误判开路 → 全模型不可用）。信号量控制全局并行度，
 * 超出的请求排队等待；排队超时抛出 {@link LlmBusyException} 由上层友好降级，
 * 而不是把压力原样传导给供应商。
 */
@Slf4j
@Component
public class LlmCallLimiter {

    private final Semaphore permits;
    private final long queueTimeoutMs;

    public LlmCallLimiter(
            @Value("${agent.llm.max-concurrent:20}") int maxConcurrent,
            @Value("${agent.llm.queue-timeout-seconds:45}") long queueTimeoutSeconds) {
        this.permits = new Semaphore(maxConcurrent, true);
        this.queueTimeoutMs = queueTimeoutSeconds * 1000;
        log.info("LLM 并发限制器初始化: maxConcurrent={}, queueTimeout={}s", maxConcurrent, queueTimeoutSeconds);
    }

    /**
     * 排队获取调用许可（公平信号量，先到先得）
     */
    public void acquire() {
        try {
            long waitStart = System.currentTimeMillis();
            if (!permits.tryAcquire(queueTimeoutMs, TimeUnit.MILLISECONDS)) {
                int queued = permits.getQueueLength();
                throw new LlmBusyException(String.format("排队超时（等待超过 %dms，当前排队 %d）", queueTimeoutMs, queued));
            }
            long waited = System.currentTimeMillis() - waitStart;
            if (waited > 1000) {
                log.info("LLM 调用排队 {}ms 后获得许可（当前排队 {}）", waited, permits.getQueueLength());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmBusyException("排队被中断");
        }
    }

    public void release() {
        permits.release();
    }

    /**
     * 包裹一次受并发限制的调用
     */
    public <T> T guard(java.util.function.Supplier<T> call) {
        acquire();
        try {
            return call.get();
        } finally {
            release();
        }
    }

    public int availablePermits() {
        return permits.availablePermits();
    }
}
