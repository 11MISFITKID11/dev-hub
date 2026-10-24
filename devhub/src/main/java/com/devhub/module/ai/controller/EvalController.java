package com.devhub.module.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.devhub.common.OperLog;
import com.devhub.module.ai.common.BusinessException;
import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.mapper.EvalCaseMapper;
import com.devhub.module.ai.mapper.EvalResultMapper;
import com.devhub.module.ai.mapper.EvalRunMapper;
import com.devhub.module.ai.model.entity.EvalCase;
import com.devhub.module.ai.model.entity.EvalResult;
import com.devhub.module.ai.model.entity.EvalRun;
import com.devhub.module.ai.service.eval.EvalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 评测集管理 + 跑批 + 历史对比（检索质量回归，对标 RAGAS 思路）
 */
@RestController
@RequestMapping("/api/v1/eval")
@RequiredArgsConstructor
public class EvalController {

    private final EvalCaseMapper caseMapper;
    private final EvalRunMapper runMapper;
    private final EvalResultMapper resultMapper;
    private final EvalService evalService;

    // ---------- 用例管理 ----------

    @GetMapping("/cases")
    public Result<Page<EvalCase>> cases(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "20") long size) {
        return Result.success(caseMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<EvalCase>().orderByDesc(EvalCase::getId)));
    }

    @OperLog(module = "AI评测", action = "新增用例")
    @PostMapping("/cases")
    public Result<EvalCase> addCase(@RequestBody EvalCase c) {
        if (c.getQuestion() == null || c.getQuestion().isBlank()) {
            throw new BusinessException("问题不能为空");
        }
        c.setId(null);
        c.setEnabled(c.getEnabled() == null ? 1 : c.getEnabled());
        caseMapper.insert(c);
        return Result.success(c);
    }

    @OperLog(module = "AI评测", action = "更新用例")
    @PutMapping("/cases/{id}")
    public Result<String> updateCase(@PathVariable Long id, @RequestBody EvalCase patch) {
        patch.setId(id);
        caseMapper.updateById(patch);
        return Result.success("ok");
    }

    @OperLog(module = "AI评测", action = "删除用例")
    @DeleteMapping("/cases/{id}")
    public Result<String> deleteCase(@PathVariable Long id) {
        caseMapper.deleteById(id);
        return Result.success("ok");
    }

    // ---------- 跑批与历史 ----------

    /** mode=retrieval（免费，走到重排）/ full（真实链路含忠实度，耗时与成本较高） */
    @OperLog(module = "AI评测", action = "跑批")
    @PostMapping("/run")
    public Result<EvalRun> run(@RequestParam(defaultValue = "retrieval") String mode) {
        return Result.success(evalService.run("full".equalsIgnoreCase(mode)));
    }

    @GetMapping("/runs")
    public Result<List<EvalRun>> runs() {
        return Result.success(runMapper.selectList(new LambdaQueryWrapper<EvalRun>()
                .orderByDesc(EvalRun::getId).last("LIMIT 10")));
    }

    @GetMapping("/runs/{id}/results")
    public Result<List<EvalResult>> results(@PathVariable Long id) {
        return Result.success(resultMapper.selectList(new LambdaQueryWrapper<EvalResult>()
                .eq(EvalResult::getRunId, id).orderByAsc(EvalResult::getId)));
    }
}
