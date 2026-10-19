package com.devhub.module.report.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.model.entity.Document;
import com.devhub.module.ai.mapper.DocumentMapper;
import com.devhub.module.defect.entity.PrjDefect;
import com.devhub.module.defect.mapper.PrjDefectMapper;
import com.devhub.module.project.entity.PrjRequirement;
import com.devhub.module.project.mapper.PrjRequirementMapper;
import com.devhub.module.task.entity.PrjTask;
import com.devhub.module.task.mapper.PrjTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全局搜索（P1，Linear 式 Cmd+K）：跨任务/缺陷/需求/文档/会话的 LIKE 联查。
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final PrjTaskMapper taskMapper;
    private final PrjDefectMapper defectMapper;
    private final PrjRequirementMapper reqMapper;
    private final DocumentMapper documentMapper;
    private final JdbcTemplate jdbc;

    @GetMapping
    public Result<Map<String, List<Map<String, Object>>>> search(@RequestParam String q) {
        String kw = q.trim();
        Map<String, List<Map<String, Object>>> out = new java.util.LinkedHashMap<>();
        if (kw.isEmpty()) return Result.success(out);
        String like = "%" + kw + "%";

        List<Map<String, Object>> tasks = new ArrayList<>();
        taskMapper.selectList(new LambdaQueryWrapper<PrjTask>()
                        .like(PrjTask::getTitle, like).orderByDesc(PrjTask::getUpdateTime).last("LIMIT 5"))
                .forEach(t -> tasks.add(Map.of("id", t.getId(), "title", t.getTitle(),
                        "status", String.valueOf(t.getStatus()), "url", "/task")));
        out.put("任务", tasks);

        List<Map<String, Object>> defects = new ArrayList<>();
        defectMapper.selectList(new LambdaQueryWrapper<PrjDefect>()
                        .like(PrjDefect::getTitle, like).orderByDesc(PrjDefect::getUpdateTime).last("LIMIT 5"))
                .forEach(d -> defects.add(Map.of("id", d.getId(), "title", d.getTitle(),
                        "status", String.valueOf(d.getStatus()), "url", "/defect")));
        out.put("缺陷", defects);

        List<Map<String, Object>> reqs = new ArrayList<>();
        reqMapper.selectList(new LambdaQueryWrapper<PrjRequirement>()
                        .like(PrjRequirement::getTitle, like).orderByDesc(PrjRequirement::getUpdateTime).last("LIMIT 5"))
                .forEach(r -> reqs.add(Map.of("id", r.getId(), "title", r.getTitle(),
                        "status", String.valueOf(r.getStatus()), "url", "/requirement")));
        out.put("需求", reqs);

        List<Map<String, Object>> docs = new ArrayList<>();
        try {
            documentMapper.selectList(new LambdaQueryWrapper<Document>()
                            .like(Document::getFileName, like).last("LIMIT 5"))
                    .forEach(d -> docs.add(Map.of("id", d.getId(), "title", d.getFileName(),
                            "status", d.getStatus() == null ? "" : d.getStatus(), "url", "/doc")));
        } catch (Exception ignore) { }
        out.put("文档", docs);

        List<Map<String, Object>> convs = new ArrayList<>();
        try {
            jdbc.queryForList("SELECT id, title FROM conversation WHERE title LIKE ? ORDER BY id DESC LIMIT 5", like)
                    .forEach(row -> convs.add(Map.of("id", row.get("id"),
                            "title", String.valueOf(row.get("title")), "status", "", "url", "/ai/history")));
        } catch (Exception ignore) { }
        out.put("会话", convs);

        return Result.success(out);
    }
}
