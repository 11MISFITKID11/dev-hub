package com.devhub.module.ai.service.rag;

import com.devhub.module.ai.infrastructure.cache.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 检索结果缓存转换单元测试（修复前：LinkedHashMap 直接返回导致下游 ClassCastException）
 */
@ExtendWith(MockitoExtension.class)
class MultiRetrieverResultCacheTest {

    @Mock
    private RedisCacheService cacheService;

    private MultiRetriever retriever;

    @BeforeEach
    void setUp() {
        // readResultCache 仅依赖 cacheService，其余构造参数与本测试无关
        retriever = new MultiRetriever(null, cacheService, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("缓存命中：LinkedHashMap 逐条转回 RetrievalResult 强类型")
    void readResultCache_convertsLinkedHashMaps() {
        Map<String, Object> element = new LinkedHashMap<>();
        element.put("chunkId", "c1");
        element.put("documentId", "d1");
        element.put("fileName", "手册.pdf");
        element.put("content", "内容");
        element.put("score", 0.87);
        element.put("source", "vector");
        element.put("docStatus", "active");
        when(cacheService.get("rag:result:v0:s:abc", List.class))
                .thenReturn(Optional.of(List.of(element)));

        List<MultiRetriever.RetrievalResult> results = retriever.readResultCache("rag:result:v0:s:abc");

        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("c1", results.get(0).getChunkId());
        assertEquals("手册.pdf", results.get(0).getFileName());
        assertEquals(0.87, results.get(0).getScore());
        assertEquals("vector", results.get(0).getSource());
    }

    @Test
    @DisplayName("缓存缺失或为空：返回 null 走正常检索")
    void readResultCache_missingOrEmpty_returnsNull() {
        when(cacheService.get(eq("k1"), eq(List.class))).thenReturn(Optional.empty());
        assertNull(retriever.readResultCache("k1"));

        when(cacheService.get(eq("k2"), eq(List.class))).thenReturn(Optional.of(List.of()));
        assertNull(retriever.readResultCache("k2"));
    }

    @Test
    @DisplayName("缓存内容损坏：清除脏 key 并按未命中返回 null（最坏退化为重新检索）")
    void readResultCache_corrupted_deletesAndReturnsNull() {
        when(cacheService.get(eq("k3"), eq(List.class)))
                .thenReturn(Optional.of(List.of("not-a-map")));

        assertNull(retriever.readResultCache("k3"));
        verify(cacheService).delete("k3");
    }
}
