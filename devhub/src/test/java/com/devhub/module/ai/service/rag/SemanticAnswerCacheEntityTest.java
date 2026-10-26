package com.devhub.module.ai.service.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 语义缓存实体守卫：数字实体（订单号等）不同的问题不得视为等价
 * （线上实测：仅单号之差的两问向量相似度 0.965，无守卫会回放旧答案）
 */
class SemanticAnswerCacheEntityTest {

    @Test
    void 订单号不同_实体不等() {
        assertFalse(SemanticAnswerCache.sameEntities(
                "订单 SO-2024-1004 发货了吗？物流到哪了？",
                "订单 SO-2024-1001 发货了吗？"));
    }

    @Test
    void 同一订单_仅措辞不同_实体相等() {
        assertTrue(SemanticAnswerCache.sameEntities(
                "订单 SO-2024-1001 发货了吗",
                "SO-2024-1001 这个订单发货没有"));
    }

    @Test
    void 双方均无数字实体_视为一致_可走缓存() {
        assertTrue(SemanticAnswerCache.sameEntities(
                "设备保修期是多久", "产品保修多长时间"));
    }

    @Test
    void 一方有实体一方没有_不等() {
        assertFalse(SemanticAnswerCache.sameEntities(
                "订单 SO-2024-1001 发货了吗", "我的订单发货了吗"));
    }
}
