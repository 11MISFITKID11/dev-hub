package com.devhub.module.ai.etl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结构感知切片 + Parent-Child 二级拆分单元测试
 */
class DocumentChunkerTest {

    private DocumentChunker chunker;

    @BeforeEach
    void setUp() {
        chunker = new DocumentChunker();
        ReflectionTestUtils.setField(chunker, "chunkSize", 100);
        ReflectionTestUtils.setField(chunker, "chunkOverlap", 20);
        ReflectionTestUtils.setField(chunker, "childSize", 40);
    }

    @Test
    @DisplayName("空文本返回空列表（上游据此报「内容为空」）")
    void chunk_blankText_returnsEmpty() {
        assertTrue(chunker.chunk("   ", "doc-1").isEmpty());
        assertTrue(chunker.chunk(null, "doc-1").isEmpty());
    }

    @Test
    @DisplayName("结构感知：按中文序号章节边界切分，索引连续、ID 唯一")
    void chunk_structuredText_splitsOnSectionBoundary() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            body.append("这是总则部分的第").append(i).append("句话，说明基本规范要求。");
        }
        StringBuilder tail = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            tail.append("这是附则部分的第").append(i).append("句话，说明生效与解释权。");
        }
        String text = "一、总则\n" + body + "\n二、附则\n" + tail;

        List<DocumentChunker.Chunk> chunks = chunker.chunk(text, "doc-1");

        assertTrue(chunks.size() >= 2, "应至少切成 2 块，实际 " + chunks.size());
        Set<String> ids = chunks.stream().map(DocumentChunker.Chunk::getId).collect(Collectors.toSet());
        assertEquals(chunks.size(), ids.size(), "chunk id 必须唯一");
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).getIndex(), "索引应从 0 连续递增");
            assertEquals("doc-1", chunks.get(i).getDocumentId());
            assertFalse(chunks.get(i).getContent().isBlank());
        }
        String joined = chunks.stream().map(DocumentChunker.Chunk::getContent).collect(Collectors.joining("\n"));
        assertTrue(joined.contains("一、总则"));
        assertTrue(joined.contains("二、附则"));
    }

    @Test
    @DisplayName("无结构回退：段落定长切片保留尾部重叠上下文")
    void chunk_plainText_fallsBackWithOverlap() {
        String text = String.join("\n\n",
                "这是一段没有任何结构标记的普通文本段落，用来触发定长回退切片逻辑。".repeat(3),
                "第二段同样没有章节标题与序号特征，继续累积触发切块边界。".repeat(3),
                "第三段内容与前两段一起验证相邻块之间的重叠保持。".repeat(3));

        List<DocumentChunker.Chunk> chunks = chunker.chunk(text, "doc-2");

        assertTrue(chunks.size() >= 2, "长文本应切成多块，实际 " + chunks.size());
        String first = chunks.get(0).getContent();
        String second = chunks.get(1).getContent();
        assertTrue(second.contains(first.substring(first.length() - 10)),
                "后一块应包含前一块的尾部重叠");
    }

    @Test
    @DisplayName("Parent-Child：小块自继承，大块按句拆分且携带 parentId")
    void splitToChildren_smallSelfBigSplit() {
        DocumentChunker.Chunk small = DocumentChunker.Chunk.builder()
                .id("p1").documentId("d").content("短内容").index(0).charCount(3).build();
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            longText.append("这是用于验证二级拆分的长句子。");
        }
        DocumentChunker.Chunk big = DocumentChunker.Chunk.builder()
                .id("p2").documentId("d").content(longText.toString()).index(1)
                .charCount(longText.length()).build();

        List<DocumentChunker.Chunk> children = chunker.splitToChildren(List.of(small, big));

        List<DocumentChunker.Chunk> ofSmall = children.stream()
                .filter(c -> "p1".equals(c.getParentId())).toList();
        List<DocumentChunker.Chunk> ofBig = children.stream()
                .filter(c -> "p2".equals(c.getParentId())).toList();

        assertEquals(1, ofSmall.size(), "小块应自继承为唯一子块");
        assertEquals("短内容", ofSmall.get(0).getContent());
        assertTrue(ofBig.size() >= 3, "长块应拆成多个子块，实际 " + ofBig.size());
        ofBig.forEach(c -> assertTrue(c.getContent().length() <= 45,
                "子块长度应约等于 childSize，实际 " + c.getContent().length()));
    }
}
