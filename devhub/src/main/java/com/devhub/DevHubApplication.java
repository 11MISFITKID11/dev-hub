package com.devhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DevHub 研发管理系统（骨架）
 * <p>
 * 宿主：登录（Spring Security + JWT）/ RBAC 菜单 / 系统表；
 * 业务模块：module-ai（自 agent-platform 平移的 RAG 智能问答）。
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class DevHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevHubApplication.class, args);
    }
}
