package com.devhub.module.ai.infrastructure.llm;

/**
 * LLM 并发已满且排队超时——属于容量保护性降级，不是系统故障，
 * 上层应返回"稍后再试"类提示而非 500。
 */
public class LlmBusyException extends RuntimeException {
    public LlmBusyException(String message) {
        super(message);
    }
}
