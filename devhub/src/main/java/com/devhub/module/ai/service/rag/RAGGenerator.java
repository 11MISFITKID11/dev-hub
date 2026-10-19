package com.devhub.module.ai.service.rag;

import com.devhub.module.ai.infrastructure.llm.ModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 答案生成器
 * <p>
 * 将检索到的相关文档注入到 Prompt 中，让 LLM 基于检索结果生成回答，
 * 实现检索增强生成（Retrieval-Augmented Generation）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGGenerator {

    private final ModelRouter modelRouter;

    private static final String RAG_PROMPT_TEMPLATE = """
            你是一个专业的企业知识库问答助手。请基于以下检索到的参考文档来回答用户的问题。

            回答要求：
            1. 只使用参考文档中的信息来回答；文档中没有相关信息时，明确回答"知识库中暂无相关信息"，不要编造
            2. 回答末尾单独一行列出引用的来源文件名，格式：来源：《文件名》
            3. 回答要准确、简洁、有条理

            参考文档：
            %s

            用户问题：%s

            请回答：
            """;

    /**
     * 基于检索结果生成回答（同步）
     *
     * @param query          用户查询
     * @param retrievedDocs  检索到的文档
     * @param forceModel     指定模型（可为 null）
     * @return 生成的回答文本
     */
    public String generate(String query, List<MultiRetriever.RetrievalResult> retrievedDocs, String forceModel) {
        String context = buildContext(retrievedDocs);
        String promptText = String.format(RAG_PROMPT_TEMPLATE, context, query);

        log.info("RAG 生成: query={}, docs={}", truncate(query, 80), retrievedDocs.size());

        String answer = modelRouter.call(promptText, forceModel);

        log.info("RAG 生成完成: answer_length={}", answer.length());
        return answer;
    }

    /**
     * 基于检索结果生成回答（流式）
     */
    public Flux<String> generateStream(String query, List<MultiRetriever.RetrievalResult> retrievedDocs, String forceModel) {
        String context = buildContext(retrievedDocs);
        String promptText = String.format(RAG_PROMPT_TEMPLATE, context, query);

        log.info("RAG 流式生成: query={}, docs={}", truncate(query, 80), retrievedDocs.size());

        return modelRouter.stream(promptText, forceModel)
                .filter(text -> !text.isEmpty());
    }

    /**
     * 将检索结果格式化为 Prompt 上下文
     */
    private String buildContext(List<MultiRetriever.RetrievalResult> docs) {
        if (docs == null || docs.isEmpty()) {
            return "（未找到相关参考文档）";
        }

        return docs.stream()
                .map(doc -> String.format(
                        "[来源文件: %s | 相关度: %.2f]\n%s",
                        doc.getFileName() != null ? doc.getFileName() : doc.getDocumentId(),
                        doc.getScore(), doc.getContent()
                ))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
