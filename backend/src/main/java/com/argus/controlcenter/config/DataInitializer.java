package com.argus.controlcenter.config;

import com.argus.controlcenter.service.InstanceService;
import com.argus.controlcenter.service.LogService;
import com.argus.controlcenter.service.NodeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 开发数据初始化器。
 *
 * <p>示例数据只用于本地演示和测试。MySQL 配置将该开关默认设为 false，避免
 * 服务第一次连接生产库时自动写入“零壹”示例数据。</p>
 */
@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner seed(
            NodeService nodes,
            InstanceService instances,
            LogService logs,
            @Value("${argus.demo-data.enabled:true}") boolean demoDataEnabled) {
        return args -> {
            if (!demoDataEnabled) {
                return;
            }
            // 按外键依赖顺序创建：节点 -> 实例 -> 日志。
            if (nodes.count() == 0) {
                nodes.seed();
            }
            if (instances.count() == 0) {
                instances.seed();
            }
            if (logs.count() == 0) {
                logs.seed();
            }
        };
    }
}
