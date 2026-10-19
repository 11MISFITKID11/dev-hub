package com.devhub.module.ai.controller;

import com.devhub.module.ai.common.Result;
import com.devhub.module.ai.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量统计控制器
 * <p>
 * 按模型聚合 Token 消耗与调用次数（成本控制的数据基础）
 */
@RestController
@RequestMapping("/api/v1/usage")
@RequiredArgsConstructor
public class UsageController {

    private final MessageMapper messageMapper;

    /**
     * 按模型聚合的 Token 用量
     */
    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        List<Map<String, Object>> byModel = messageMapper.usageByModel();

        long totalTokens = byModel.stream()
                .mapToLong(m -> ((Number) m.getOrDefault("tokens", 0)).longValue())
                .sum();
        long totalCalls = byModel.stream()
                .mapToLong(m -> ((Number) m.getOrDefault("calls", 0)).longValue())
                .sum();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("byModel", byModel);
        data.put("totalTokens", totalTokens);
        data.put("totalCalls", totalCalls);
        return Result.success(data);
    }
}
