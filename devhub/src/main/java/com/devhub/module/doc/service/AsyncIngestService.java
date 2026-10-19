package com.devhub.module.doc.service;

import com.devhub.module.ai.etl.ETLPipeline;
import com.devhub.module.ai.mapper.DocumentMapper;
import com.devhub.module.ai.model.dto.DocumentUploadResponse;
import com.devhub.module.ai.model.entity.Document;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 文档中心异步入库（方案 5.5：平移变融合的关键）
 * <p>
 * 上传接口立即返回：预插 PROCESSING 外壳行（带分类/上传人/部门）→ 发事件 →
 * 事件监听异步执行 Tika 解析 → 切片 → 向量化 → 更新行为 completed + chunk_count。
 * 列表页轮询即可看到「解析中 → 已生效（N 切片）」的状态流转。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncIngestService {

    private final DocumentMapper documentMapper;
    private final ETLPipeline etlPipeline;
    private final ApplicationEventPublisher eventPublisher;
    private final JdbcTemplate jdbc;

    /** 上传事件：临时文件路径（请求结束后 MultipartFile 即失效，先落盘再异步读） */
    public record DocumentIngestEvent(String documentId, String fileName, String contentType,
                                      Path tempFile, long fileSize) {}

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

        // PROCESSING 外壳行（文档中心列表立即可见"解析中"）
        Document shell = new Document();
        shell.setId(documentId);
        shell.setFileName(fileName);
        shell.setFileType(extOf(fileName));
        shell.setFileSize(file.getSize());
        shell.setChunkCount(0);
        shell.setStatus("processing");
        shell.setLifeStatus("active");
        shell.setSourceType("upload");
        shell.setCategoryId(categoryId);
        shell.setUploader(uploader);
        shell.setAuditStatus("PENDING");
        shell.setDeptId(deptId);
        documentMapper.insert(shell);

        // 字节落临时文件（异步线程读），发事件驱动 ETL
        try {
            String suffix = fileName != null && fileName.contains(".")
                    ? fileName.substring(fileName.lastIndexOf('.')) : ".tmp";
            Path temp = Files.createTempFile("devhub-ingest-", suffix);
            file.transferTo(temp.toFile());
            eventPublisher.publishEvent(new DocumentIngestEvent(
                    documentId, fileName, file.getContentType(), temp, file.getSize()));
        } catch (Exception e) {
            // 落盘失败：外壳行标记失败并直接补偿删除（不留幽灵行）
            log.error("上传暂存失败: {}", e.getMessage());
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

    /** 事件监听：异步执行 ETL 管道（解析→切片→向量化→生命周期治理） */
    @Async
    @org.springframework.context.event.EventListener
    public void onIngest(DocumentIngestEvent event) {
        try {
            etlPipeline.processText(event.documentId(), event.fileName(),
                    parseText(event), "upload", "active", null, event.fileSize());
            log.info("异步入库完成: id={}, file={}", event.documentId(), event.fileName());
        } catch (Exception e) {
            // 失败：ETL 内部补偿已删外壳行（无幽灵行），记录日志供排查
            log.error("异步入库失败（外壳行已补偿清理）: id={}, file={}, cause={}",
                    event.documentId(), event.fileName(), e.getMessage());
        } finally {
            try { Files.deleteIfExists(event.tempFile()); } catch (Exception ignored) { }
        }
    }

    private String parseText(DocumentIngestEvent event) throws Exception {
        Path temp = event.tempFile();
        MultipartFile wrapper = new ByteArrayMultipartFile(
                event.fileName(), event.contentType(), Files.readAllBytes(temp));
        return new com.devhub.module.ai.etl.DocumentParser().parse(wrapper);
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
