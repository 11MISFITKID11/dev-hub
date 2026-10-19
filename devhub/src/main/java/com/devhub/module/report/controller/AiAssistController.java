package com.devhub.module.report.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.infrastructure.llm.ModelRouter;
import com.devhub.module.defect.entity.PrjDefect;
import com.devhub.module.defect.mapper.PrjDefectMapper;
import com.devhub.module.task.entity.PrjTask;
import com.devhub.module.task.mapper.PrjTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * P2 AI 反哺（方案 feature-deepening-plan）：
 * 8. 缺陷相似检索（提缺陷前向量搜历史 Top3，复用 Milvus 通道）
 * 10. 周报生成器（聚合本地数据 → LLM 生成 Markdown）
 */
@Slf4j
@RestController
@RequestMapping("/api/ai-assist")
@RequiredArgsConstructor
public class AiAssistController {

    private final ModelRouter modelRouter;
    private final PrjDefectMapper defectMapper;
    private final PrjTaskMapper taskMapper;
    private final JdbcTemplate jdbc;
    private final com.devhub.module.defect.service.DefectVectorService vectorService;

    /** 相似缺陷 Top3：向量语义检索优先（方案 P2-8），向量通道不可用时回退 ngram 关键词 */
    @GetMapping("/defects/{id}/similar")
    public Result<List<Map<String, Object>>> similarDefects(@PathVariable Long id) {
        PrjDefect d = defectMapper.selectById(id);
        if (d == null) return Result.fail(404, "缺陷不存在");

        List<PrjDefect> all = defectMapper.selectList(new LambdaQueryWrapper<PrjDefect>()
                .ne(PrjDefect::getId, id));
        List<Object[]> hits = all.isEmpty() ? List.of() : vectorService.search(d, all, 3);
        if (hits != null && !hits.isEmpty()) {
            List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (Object[] h : hits) {
                Long did = (Long) h[0];
                PrjDefect target = all.stream().filter(x -> x.getId().equals(did)).findFirst().orElse(null);
                if (target == null) continue;
                out.add(Map.of("id", target.getId(), "title", target.getTitle(),
                        "severity", target.getSeverity() == null ? "NORMAL" : target.getSeverity(),
                        "status", target.getStatus(), "score", h[1]));
            }
            if (!out.isEmpty()) return Result.success(out);
        }
        // 回退：MySQL ngram 全文通道
        List<Map<String, Object>> fallback = jdbc.queryForList(
                "SELECT id, title, severity, status, " +
                "       MATCH(title) AGAINST(? IN NATURAL LANGUAGE MODE) AS score " +
                "FROM prj_defect WHERE deleted = 0 AND id != ? " +
                "  AND MATCH(title) AGAINST(? IN NATURAL LANGUAGE MODE) " +
                "ORDER BY score DESC LIMIT 3",
                d.getTitle(), id, d.getTitle());
        return Result.success(fallback);
    }

    /** 周报生成：本周完成/进行中/新增缺陷/AI 问答高频 → Markdown */
    @PostMapping("/weekly-report")
    public Result<String> weeklyReport() {
        String me = currentUser();
        var since = java.time.LocalDate.now().minusDays(7).atStartOfDay();

        List<PrjTask> done = taskMapper.selectList(new LambdaQueryWrapper<PrjTask>()
                .eq(PrjTask::getAssigneeUsername, me)
                .isNotNull(PrjTask::getDoneAt).ge(PrjTask::getDoneAt, since));
        List<PrjTask> doing = taskMapper.selectList(new LambdaQueryWrapper<PrjTask>()
                .eq(PrjTask::getAssigneeUsername, me)
                .in(PrjTask::getStatus, "TODO", "DOING"));
        Long myDefects = defectMapper.selectCount(new LambdaQueryWrapper<PrjDefect>()
                .eq(PrjDefect::getReporterUsername, me).ge(PrjDefect::getCreateTime, since));
        List<String> topQuestions = jdbc.queryForList(
                "SELECT content FROM message WHERE role = 'USER' AND create_time >= ? " +
                "ORDER BY id DESC LIMIT 20", String.class, since);

        String facts = """
                本周完成任务（%d 个）：%s
                进行中/待办（%d 个）：%s
                本周提交缺陷：%d 个
                近期 AI 问答记录（截选）：%s
                """.formatted(
                done.size(), done.stream().map(PrjTask::getTitle).toList(),
                doing.size(), doing.stream().map(PrjTask::getTitle).limit(8).toList(),
                myDefects,
                topQuestions.stream().map(q -> q.substring(0, Math.min(40, q.length()))).limit(8).toList());

        String prompt = """
                你是研发周报助手。基于以下客观数据生成本周工作周报（Markdown 格式），
                包含：本周完成、下周计划、风险与求助三个段落。只使用给定数据，不要编造。

                %s
                """.formatted(facts);

        try {
            return Result.success(modelRouter.call(prompt, null));
        } catch (Exception e) {
            log.warn("周报生成失败: {}", e.getMessage());
            return Result.fail(500, "周报生成失败：" + e.getMessage());
        }
    }

    private String currentUser() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
