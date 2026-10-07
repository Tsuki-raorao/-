-- 只增加可重建的运行时快照字段，不改变现有主键和外键。
ALTER TABLE instances ADD COLUMN cpu_percent DECIMAL(10,2) NOT NULL DEFAULT 0;
ALTER TABLE instances ADD COLUMN memory_bytes BIGINT NOT NULL DEFAULT 0;
ALTER TABLE instances ADD COLUMN player_count INT NOT NULL DEFAULT 0;
