package com.devhub.module.ai.infrastructure.llm;

/**
 * 单次请求的 Token 用量聚合器
 * <p>
 * ModelRouter 每次模型调用后累计（含改写/重排等中间调用），
 * 编排在持久化对话时一次性取走写入 message 表。
 */
public class UsageContext {

    private static final ThreadLocal<long[]> HOLDER = new ThreadLocal<>();

    public static void begin() {
        HOLDER.set(new long[]{0, 0});
    }

    public static void add(long inputTokens, long outputTokens) {
        long[] usage = HOLDER.get();
        if (usage != null) {
            usage[0] += Math.max(0, inputTokens);
            usage[1] += Math.max(0, outputTokens);
        }
    }

    /** 取走并清零 */
    public static long[] take() {
        long[] usage = HOLDER.get();
        HOLDER.remove();
        return usage == null ? new long[]{0, 0} : usage;
    }

    /**
     * 快照当前用量（不清零）——供异步线程继承上下文：
     * ThreadLocal 在 @Async/线程切换后会丢失，跨线程计账先快照再恢复
     */
    public static long[] snapshot() {
        long[] usage = HOLDER.get();
        return usage == null ? new long[]{0, 0} : new long[]{usage[0], usage[1]};
    }

    /** 以初值开启新的计账上下文（异步线程用） */
    public static void begin(long[] initial) {
        HOLDER.set(new long[]{Math.max(0, initial[0]), Math.max(0, initial[1])});
    }
}
