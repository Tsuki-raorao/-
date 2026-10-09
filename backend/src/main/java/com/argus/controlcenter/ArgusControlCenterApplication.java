package com.argus.controlcenter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.argus.controlcenter.config.AgentGatewayProperties;
import com.argus.controlcenter.config.SecurityProperties;
import com.argus.controlcenter.config.TaskControlProperties;
import com.argus.controlcenter.config.TaskReviewProperties;
import com.argus.controlcenter.identity.IdentityModeProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({AgentGatewayProperties.class, SecurityProperties.class, TaskControlProperties.class,
        TaskReviewProperties.class, IdentityModeProperties.class})
/** 控制中心启动入口，负责加载 API、数据库迁移、定时同步和安全配置。 */
public class ArgusControlCenterApplication {
    public static void main(String[] args) {
        SpringApplication.run(ArgusControlCenterApplication.class, args);
    }
}
