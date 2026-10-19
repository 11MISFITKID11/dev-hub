package com.devhub.module.doc.service;

import com.devhub.module.ai.mapper.DocumentMapper;
import com.devhub.module.ai.model.entity.Document;
import com.devhub.module.doc.config.DocIngestStreamConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Stream 消费链路单元测试：成功 ACK / 失败留 PENDING / PENDING 恢复 / 死信清理
 */
@ExtendWith(MockitoExtension.class)
class DocIngestStreamConsumerTest {

    private static final String STREAM = DocIngestStreamConfig.STREAM_KEY;
    private static final String GROUP = DocIngestStreamConfig.GROUP;

    @Mock
    private AsyncIngestService asyncIngestService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private StreamOperations<String, Object, Object> streamOps;
    @Mock
    private DocumentMapper documentMapper;

    @TempDir
    Path tempDir;

    private DocIngestStreamConsumer consumer;
    private Path tempFile;

    @BeforeEach
    void setUp() throws Exception {
        consumer = new DocIngestStreamConsumer(asyncIngestService, redisTemplate, documentMapper);
        tempFile = Files.createFile(tempDir.resolve("devhub-ingest-test.pdf"));
    }

    private Map<String, String> payload() {
        Map<String, String> map = new HashMap<>();
        map.put("documentId", "doc-1");
        map.put("fileName", "test.pdf");
        map.put("contentType", "application/pdf");
        map.put("tempPath", tempFile.toAbsolutePath().toString());
        map.put("fileSize", "1024");
        map.put("uploader", "admin");
        map.put("categoryId", "");
        map.put("deptId", "");
        return map;
    }

    @SuppressWarnings("unchecked")
    private MapRecord<String, String, String> message(RecordId id) {
        return (MapRecord<String, String, String>) (MapRecord<?, ?, ?>) record(id, payload());
    }

    @SuppressWarnings("unchecked")
    private MapRecord<String, Object, Object> record(RecordId id, Map<String, String> map) {
        MapRecord<String, Object, Object> rec =
                (MapRecord<String, Object, Object>) (MapRecord<?, ?, ?>)
                        org.springframework.data.redis.connection.stream.StreamRecords
                                .newRecord().in(STREAM).withId(id).ofMap((Map) map);
        return rec;
    }

    @Test
    @DisplayName("消费成功：ACK + 清理临时文件")
    void onMessage_success_acksAndCleansTemp() {
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        RecordId id = RecordId.of("1-1");

        consumer.onMessage(message(id));

        verify(streamOps).acknowledge(STREAM, GROUP, id);
        assertFalse(Files.exists(tempFile), "临时文件应被清理");
    }

    @Test
    @DisplayName("消费失败：不 ACK 留在 PENDING，临时文件保留供重试")
    void onMessage_failure_keepsPendingAndTemp() {
        doThrow(new RuntimeException("Milvus 不可用"))
                .when(asyncIngestService).processIngest(any());
        RecordId id = RecordId.of("1-2");

        consumer.onMessage(message(id));

        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId[].class));
        assertTrue(Files.exists(tempFile), "临时文件应保留供重试");
    }

    @Test
    @DisplayName("PENDING 恢复：空闲超阈值且未达投递上限 → 认领重试")
    void recover_claimsAndRetriesStaleMessage() {
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        RecordId id = RecordId.of("5-1");
        PendingMessages pending = mock(PendingMessages.class);
        PendingMessage pm = mock(PendingMessage.class);
        when(pending.isEmpty()).thenReturn(false);
        when(pending.iterator()).thenReturn(List.of(pm).iterator());
        when(pm.getId()).thenReturn(id);
        when(pm.getElapsedTimeSinceLastDelivery()).thenReturn(Duration.ofMinutes(3));
        when(pm.getTotalDeliveryCount()).thenReturn(1L);
        when(streamOps.pending(eq(STREAM), eq(GROUP), any(), eq(50L))).thenReturn(pending);
        when(streamOps.claim(eq(STREAM), eq(GROUP), anyString(),
                eq(Duration.ofMinutes(2)), any(RecordId[].class)))
                .thenReturn(List.of(record(id, payload())));

        consumer.recoverPendingMessages();

        verify(asyncIngestService).processIngest(any());
        verify(streamOps).acknowledge(STREAM, GROUP, id);
        assertFalse(Files.exists(tempFile));
    }

    @Test
    @DisplayName("死信：投递超上限且外壳行仍 processing → 删行 + ACK + 清临时文件")
    void deadLetter_cleansShellRowAndAcks() {
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        RecordId id = RecordId.of("9-9");
        PendingMessages pending = mock(PendingMessages.class);
        PendingMessage pm = mock(PendingMessage.class);
        when(pending.isEmpty()).thenReturn(false);
        when(pending.iterator()).thenReturn(List.of(pm).iterator());
        when(pm.getId()).thenReturn(id);
        when(pm.getElapsedTimeSinceLastDelivery()).thenReturn(Duration.ofMinutes(10));
        when(pm.getTotalDeliveryCount()).thenReturn(3L);
        when(streamOps.pending(eq(STREAM), eq(GROUP), any(), eq(50L))).thenReturn(pending);
        when(streamOps.claim(eq(STREAM), eq(GROUP), anyString(),
                eq(Duration.ZERO), any(RecordId[].class)))
                .thenReturn(List.of(record(id, payload())));

        Document shell = new Document();
        shell.setId("doc-1");
        shell.setStatus("processing");
        when(documentMapper.selectById("doc-1")).thenReturn(shell);

        consumer.recoverPendingMessages();

        verify(asyncIngestService, never()).processIngest(any());
        verify(documentMapper).deleteById("doc-1");
        verify(streamOps).acknowledge(STREAM, GROUP, id);
        assertFalse(Files.exists(tempFile), "死信临时文件应清理");
    }
}
