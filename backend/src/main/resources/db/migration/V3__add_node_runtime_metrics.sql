-- 保存 Agent 上报的主机资源快照；默认值保证旧节点可以平滑升级。
ALTER TABLE nodes ADD COLUMN cpu_percent DECIMAL(10,2) NOT NULL DEFAULT 0;
ALTER TABLE nodes ADD COLUMN memory_bytes BIGINT NOT NULL DEFAULT 0;
ALTER TABLE nodes ADD COLUMN memory_total_bytes BIGINT NOT NULL DEFAULT 0;
