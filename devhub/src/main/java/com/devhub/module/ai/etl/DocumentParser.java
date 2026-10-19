package com.devhub.module.ai.etl;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * 文档解析器
 * <p>
 * 使用 Apache Tika 解析多种文档格式，提取纯文本内容。
 * 支持 PDF、DOCX、TXT、HTML、Markdown 等。
 * <p>
 * 关键点：必须把文件名传入 Metadata——DOCX 的 magic bytes 是 zip，
 * 纯流检测会被识别为压缩包而只列出内部文件；携带 .docx 后缀才能
 * 走 OfficeParser 提取 Word 正文。
 */
@Slf4j
@Component
public class DocumentParser {

    private final Tika tika = new Tika();

    /**
     * 解析上传文件，提取文本内容
     *
     * @param file 上传的文件
     * @return 提取的文本内容
     */
    public String parse(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        log.info("开始解析文档: fileName={}, contentType={}", fileName, file.getContentType());

        try (InputStream inputStream = file.getInputStream()) {
            Metadata metadata = new Metadata();
            if (fileName != null) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
            }
            if (file.getContentType() != null) {
                metadata.set(Metadata.CONTENT_TYPE, file.getContentType());
            }

            tika.setMaxStringLength(10 * 1024 * 1024);
            String content = tika.parseToString(inputStream, metadata);

            content = cleanText(content);

            log.info("文档解析完成: fileName={}, textLength={}", fileName, content.length());
            return content;
        } catch (Exception e) {
            log.error("文档解析失败: fileName={}", fileName, e);
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 清洗文本：去除多余空白、特殊字符
     */
    private String cleanText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text
                .replaceAll("\\r\\n", "\n")
                .replaceAll("\\r", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .replaceAll("[ \\t]{2,}", " ")
                .trim();
    }
}
