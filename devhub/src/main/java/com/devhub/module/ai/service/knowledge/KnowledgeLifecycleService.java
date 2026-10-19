package com.devhub.module.ai.service.knowledge;

import com.devhub.module.ai.infrastructure.llm.ModelRouter;
import com.devhub.module.ai.infrastructure.vectordb.MilvusService;
import com.devhub.module.ai.mapper.DocumentMapper;
import com.devhub.module.ai.model.entity.Document;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识生命周期服务（知识冲突治理核心）
 * <p>
 * 上传时自动执行四项治理：
 * <ol>
 *   <li>版本链：同名文档新版上传，旧版自动 superseded（检索默认只取最新版）</li>
 *   <li>生效日期抽取："自X年X月X日起施行" → 旧版到期时间</li>
 *   <li>URL 提取：供定时探活识别失效链接</li>
 *   <li>冲突校验：新内容与现库高相似内容 LLM 判定（互补/重复/矛盾）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeLifecycleService {

    private final DocumentMapper documentMapper;
    private final MilvusService milvusService;
    private final ModelRouter modelRouter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern EFFECTIVE_DATE_PATTERN =
            Pattern.compile("自(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日\\s*起");
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");
    private static final double CONFLICT_SIMILARITY = 0.85;

    /**
     * 上传完成后的治理流程（返回上传结果提示）
     */
    public String onDocumentIngested(String documentId, String fileName, String text,
                                     List<List<Float>> chunkVectors) {
        StringBuilder notes = new StringBuilder();

        // 1. 版本链 + 生效日期
        LocalDateTime effectiveDate = extractEffectiveDate(text);
        retirePreviousVersion(documentId, fileName, effectiveDate, notes);

        // 2. URL 提取
        List<String> urls = extractUrls(text);
        Document doc = documentMapper.selectById(documentId);
        if (doc != null) {
            doc.setVersion(doc.getVersion() == null ? 1 : doc.getVersion());
            if (effectiveDate != null) {
                doc.setExpireAt(null);
            }
            if (!urls.isEmpty()) {
                try {
                    doc.setUrls(objectMapper.writeValueAsString(urls));
                } catch (Exception ignored) {
                }
                notes.append("检测到 ").append(urls.size()).append(" 个外部链接（将定期探活）；");
            }
            documentMapper.updateById(doc);
        }

        // 3. 冲突校验（用首个 chunk 向量找现库相似内容）
        if (chunkVectors != null && !chunkVectors.isEmpty()) {
            String conflict = detectConflict(text, chunkVectors.get(0));
            if (conflict != null) {
                Document d = documentMapper.selectById(documentId);
                if (d != null) {
                    d.setLifeStatus("conflict");
                    documentMapper.updateById(d);
                }
                notes.append("⚠ 检测到与现有知识疑似").append(conflict).append("，已标记待管理员审核；");
            }
        }

        return notes.toString();
    }

    /**
     * 同名旧版挂版本链（superseded），生效日在未来则旧版到期日=新版生效日
     */
    private void retirePreviousVersion(String newDocId, String fileName,
                                       LocalDateTime newEffectiveDate, StringBuilder notes) {
        List<Document> previous = documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getFileName, fileName)
                        .eq(Document::getLifeStatus, "active")
                        .ne(Document::getId, newDocId));
        if (previous.isEmpty()) {
            return;
        }

        Document newest = previous.get(0);
        int newVersion = previous.stream().mapToInt(d -> d.getVersion() == null ? 1 : d.getVersion())
                .max().orElse(1) + 1;
        Document current = documentMapper.selectById(newDocId);
        if (current != null) {
            current.setVersion(newVersion);
            documentMapper.updateById(current);
        }

        for (Document old : previous) {
            old.setLifeStatus("superseded");
            old.setSupersededBy(newDocId);
            if (newEffectiveDate != null && newEffectiveDate.isAfter(LocalDateTime.now())) {
                old.setExpireAt(newEffectiveDate);
            }
            documentMapper.updateById(old);
        }
        notes.append("检测到同名旧版（v").append(newVersion - 1).append("），已自动挂入版本链；");
        log.info("版本链更新: {} v{} superseded by {}", fileName, newest.getVersion(), newDocId);
    }

    /**
     * 抽取"自X年X月X日起施行"生效日期
     */
    LocalDateTime extractEffectiveDate(String text) {
        Matcher m = EFFECTIVE_DATE_PATTERN.matcher(text == null ? "" : text);
        if (m.find()) {
            try {
                return LocalDateTime.parse(
                        String.format("%s-%02d-%02dT00:00:00", m.group(1),
                                Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))),
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    List<String> extractUrls(String text) {
        return URL_PATTERN.matcher(text == null ? "" : text).results()
                .map(r -> r.group().replaceAll("[)。，.,]$", ""))
                .distinct()
                .limit(20)
                .toList();
    }

    /**
     * 冲突校验：与现库高相似内容 LLM 判定，返回 "矛盾"/"重复" 或 null（正常）
     */
    private String detectConflict(String newText, List<Float> vector) {
        try {
            List<String> similarTexts = milvusService.searchSimilarTexts(vector, 3);
            if (similarTexts.isEmpty()) {
                return null;
            }
            String joined = String.join("\n---\n", similarTexts.stream().limit(2).toList());
            String verdict = modelRouter.call(String.format("""
                    你是知识库冲突检测器。判断【新知识】与【现有知识】的关系：
                    - complementary：内容互补或不同主题
                    - duplicate：内容重复（表述不同但信息相同）
                    - contradictory：存在矛盾（同一事项给出不同规定，如金额/天数/流程不同）

                    【新知识】：%s

                    【现有知识】：%s

                    只输出一个单词：complementary / duplicate / contradictory
                    """, truncate(newText, 800), truncate(joined, 800)), null).trim().toLowerCase();

            if (verdict.contains("contradictory")) {
                log.warn("冲突检测：新知识与现有知识矛盾");
                return "矛盾";
            }
            if (verdict.contains("duplicate")) {
                return "重复";
            }
            return null;
        } catch (Exception e) {
            log.warn("冲突校验失败（跳过）: {}", e.getMessage());
            return null;
        }
    }

    private String truncate(String text, int max) {
        return text != null && text.length() > max ? text.substring(0, max) : text;
    }
}
