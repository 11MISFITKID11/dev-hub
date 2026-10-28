package com.devhub.module.ai.service.tool;

/**
 * 会话上下文透传（ThreadLocal）
 * <p>
 * 工具执行线程与编排线程一致（含流式路径的 boundedElastic），
 * 但 SecurityContext 在该线程不可用——建单等工具需要知道"当前会话"以联动
 * 人工接管，由编排层在调用 Agent 前设置、finally 清理。
 */
public final class ConversationContextHolder {

    private static final ThreadLocal<String> CONVERSATION_ID = new ThreadLocal<>();

    private ConversationContextHolder() {
    }

    public static void set(String conversationId) {
        CONVERSATION_ID.set(conversationId);
    }

    public static String get() {
        return CONVERSATION_ID.get();
    }

    public static void clear() {
        CONVERSATION_ID.remove();
    }
}
