package com.devhub.module.doc.service;

import com.devhub.module.ai.etl.ETLPipeline;
import com.devhub.module.ai.mapper.DocumentMapper;
import com.devhub.module.ai.model.dto.DocumentUploadResponse;
import com.devhub.module.ai.model.entity.Document;
import com.devhub.module.doc.config.DocIngestStreamConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 文档中心异步入库（Redis Stream 驱动，替代原 @Async 事件）
 * <p>
 * 上传接口立即返回：预插 PROCESSING 外壳行（带分类/上传人/部门）→
 * XADD 到 devhub:stream:doc-ingest → 消费者组解析 → 切片 → 向量化 → 更新行为 completed。
 * <p>
 * 相比 @Async（进程内队列，重启即丢）：Stream 持久化在 Redis，应用重启后
 * PENDING 消息被恢复任务认领重试，达到投递上限走死信清理，链路见
 * {@link DocIngestStreamConsumer}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncIngestService {

    private final DocumentMapper documentMapper;
    private final ETLPipeline etlPipeline;
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbc;

    /** 入库任务：临时文件路径 + 重建外壳行所需元数据（临时文件在处理成功/死信前保留） */
    public record DocumentIngestEvent(String documentId, String fileName, String contentType,
                                      Path tempFile, long fileSize, String uploader,
                                      Long categoryId, Long deptId) {}

    /**
     * 提交异步解析（上传接口同步部分，毫秒级返回）
     */
    public DocumentUploadResponse submit(MultipartFile file, Long categoryId) {
        String documentId = UUID.randomUUID().toString().replace("-", "");
        String fileName = file.getOriginalFilename();
        String uploader = currentUsername();

        // 部门数据域：上传人的部门；查不到挂公共（NULL）
        Long deptId = null;
        try {
            deptId = jdbc.queryForObject(
                    "SELECT dept_id FROM sys_user WHERE username = ? AND deleted = 0",
                    Long.class, uploader);
        } catch (Exception ignored) { }

        insertShell(documentId, fileName, file.getSize(), uploader, categoryId, deptId);

        // 字节落临时文件，任务描述写入 Redis Stream（MAXLEN 限长防膨胀）
        try {
            String suffix = fileName != null && fileName.contains(".")
                    ? fileName.substring(fileName.lastIndexOf('.')) : ".tmp";
            Path temp = Files.createTempFile("devhub-ingest-", suffix);
            file.transferTo(temp.toFile());

            Map<String, String> payload = new HashMap<>();
            payload.put("documentId", documentId);
            payload.put("fileName", fileName);
            payload.put("contentType", file.getContentType());
            payload.put("tempPath", temp.toAbsolutePath().toString());
            payload.put("fileSize", String.valueOf(file.getSize()));
            payload.put("uploader", uploader);
            payload.put("categoryId", categoryId == null ? "" : String.valueOf(categoryId));
            payload.put("deptId", deptId == null ? "" : String.valueOf(deptId));
            redisTemplate.opsForStream().add(
                    StreamRecords.newRecord().ofMap(payload)
                            .withStreamKey(DocIngestStreamConfig.STREAM_KEY),
                    XAddOptions.maxlen(DocIngestStreamConfig.MAX_STREAM_LEN)
                            .approximateTrimming(true));
            log.info("入库任务已入队: id={}, file={}", documentId, fileName);
        } catch (Exception e) {
            // 入队失败：外壳行标记失败并直接补偿删除（不留幽灵行）
            log.error("入库任务入队失败: {}", e.getMessage());
            documentMapper.deleteById(documentId);
            throw new RuntimeException("上传暂存失败: " + e.getMessage(), e);
        }

        return DocumentUploadResponse.builder()
                .documentId(documentId)
                .fileName(fileName)
                .chunkCount(0)
                .status("processing")
                .build();
    }

    /**
     * 消费端处理（由 Stream 消费者调用，可能重复投递）
     * <p>
     * 幂等依据：外壳行 completed = 已成功，直接跳过；行缺失 = 上次尝试失败被补偿清理，
     * 重建外壳行后重跑（元数据从 Stream 消息还原，分类/上传人/部门不丢失）。
     */
    public void processIngest(DocumentIngestEvent event) {
        Document doc = documentMapper.selectById(event.documentId());
        if (doc != null && "completed".equals(doc.getStatus())) {
            log.info("重复投递跳过（文档已完成入库）: id={}", event.documentId());
            return;
        }
        if (doc == null) {
            insertShell(event.documentId(), event.fileName(), event.fileSize(),
                    event.uploader(), event.categoryId(), event.deptId());
        }
        etlPipeline.processText(event.documentId(), event.fileName(),
                parseText(event), "upload", "active", null, event.fileSize());
        log.info("异步入库完成: id={}, file={}", event.documentId(), event.fileName());
    }

    /** PROCESSING 外壳行（文档中心列表立即可见"解析中"） */
    private void insertShell(String documentId, String fileName, long fileSize,
                             String uploader, Long categoryId, Long deptId) {
        Document shell = new Document();
        shell.setId(documentId);
        shell.setFileName(fileName);
        shell.setFileType(extOf(fileName));
        shell.setFileSize(fileSize);
        shell.setChunkCount(0);
        shell.setStatus("processing");
        shell.setLifeStatus("active");
        shell.setSourceType("upload");
        shell.setCategoryId(categoryId);
        shell.setUploader(uploader);
        shell.setAuditStatus("PENDING");
        shell.setDeptId(deptId);
        documentMapper.insert(shell);
    }

    private String parseText(DocumentIngestEvent event) {
        try {
            Path temp = event.tempFile();
            MultipartFile wrapper = new ByteArrayMultipartFile(
                    event.fileName(), event.contentType(), Files.readAllBytes(temp));
            return new com.devhub.module.ai.etl.DocumentParser().parse(wrapper);
        } catch (Exception e) {
            throw new RuntimeException("临时文件解析失败（可能已被清理）: " + e.getMessage(), e);
        }
    }

    private String currentUsername() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            return (auth != null && auth.isAuthenticated()
                    && !"anonymousUser".equals(auth.getName())) ? auth.getName() : "system";
        } catch (Exception e) { return "system"; }
    }

    private String extOf(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "unknown";
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    /** 字节数组转 MultipartFile（复用 DocumentParser/ETL 的既有签名） */
    public static class ByteArrayMultipartFile implements MultipartFile {
        private final String name;
        private final String contentType;
        private final byte[] bytes;

        public ByteArrayMultipartFile(String name, String contentType, byte[] bytes) {
            this.name = name == null ? "file" : name;
            this.contentType = contentType;
            this.bytes = bytes;
        }
        public String getName() { return "file"; }
        public String getOriginalFilename() { return name; }
        public String getContentType() { return contentType; }
        public boolean isEmpty() { return bytes == null || bytes.length == 0; }
        public long getSize() { return bytes.length; }
        public byte[] getBytes() { return bytes; }
        public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        public void transferTo(File dest) throws java.io.IOException {
            try (FileOutputStream out = new FileOutputStream(dest)) { out.write(bytes); }
        }
    }
}
