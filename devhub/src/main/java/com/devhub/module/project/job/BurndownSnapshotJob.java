package com.devhub.module.project.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devhub.module.project.entity.PrjBurndown;
import com.devhub.module.project.entity.PrjIteration;
import com.devhub.module.project.mapper.PrjBurndownMapper;
import com.devhub.module.project.mapper.PrjIterationMapper;
import com.devhub.module.task.entity.PrjTask;
import com.devhub.module.task.mapper.PrjTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 迭代燃尽快照定时任务（方案 P0-3）：每日 23:30 对"进行中"迭代落当日快照
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BurndownSnapshotJob {

    private final PrjIterationMapper iterationMapper;
    private final PrjTaskMapper taskMapper;
    private final PrjBurndownMapper burndownMapper;

    @Scheduled(cron = "0 30 23 * * *")
    public void snapshot() {
        List<PrjIteration> active = iterationMapper.selectList(
                new LambdaQueryWrapper<PrjIteration>().eq(PrjIteration::getStatus, 0));
        LocalDate today = LocalDate.now();
        for (PrjIteration it : active) {
            List<PrjTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<PrjTask>()
                    .eq(PrjTask::getIterationId, it.getId()));
            long done = tasks.stream().filter(t -> "DONE".equals(t.getStatus())).count();
            PrjBurndown snap = new PrjBurndown();
            snap.setIterationId(it.getId());
            snap.setSnapshotDate(today);
            snap.setTotalTasks(tasks.size());
            snap.setDoneTasks((int) done);
            snap.setOpenTasks(tasks.size() - (int) done);
            burndownMapper.insert(snap); // 唯一键 uk_it_date 幂等，重复执行报错由全局处理
        }
        log.info("燃尽快照完成：{} 个进行中迭代", active.size());
    }
}
