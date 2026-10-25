package com.devhub.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import java.sql.Connection;

/**
 * 演示数据一键装载（W4-9，demo profile）：
 * <p>{@code java -jar app.jar --spring.profiles.active=demo} —— 启动时幂等执行
 * classpath:demo/demo_tickets.sql（30 张云帆领域全生命周期工单，分布参考 Endava 真实数据集）。
 * <p>刻意不用 Flyway repeatable migration（R__）：R__ 校验和变化即重放，会清掉运行期真实产生的
 * 工单/评价（方案评审时的修订项）；demo profile 由人显式开启，语义是"重置演示环境"。
 */
@Slf4j
@Component
@Profile("demo")
@RequiredArgsConstructor
public class DemoDataRunner implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final javax.sql.DataSource dataSource;

    @Override
    public void run(String... args) throws Exception {
        Integer marker = jdbc.queryForObject(
                "SELECT COUNT(*) FROM support_ticket WHERE id > 100", Integer.class);
        if (marker != null && marker > 0) {
            log.info("[demo] 演示工单已存在（{} 张），跳过装载；如需重置先清 id>100 的数据", marker);
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("demo/demo_tickets.sql"));
            log.info("[demo] 演示数据装载完成：{} 张工单",
                    jdbc.queryForObject("SELECT COUNT(*) FROM support_ticket WHERE id > 100", Integer.class));
        }
    }
}
