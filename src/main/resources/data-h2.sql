-- 本地演示用合成数据：默认升级阶梯阈值配置（版本 1，阈值 10/20/40/70/100，冷却 30 分钟）。
-- 仅本地运行入口加载（application.yaml 的 spring.sql.init.data-locations），测试环境不加载本文件。
-- 幂等写法：已存在任意配置时不重复插入。

INSERT INTO incident_ladder_configs (config_version, level1_threshold, level2_threshold,
        level3_threshold, level4_threshold, level5_threshold, cooldown_minutes, created_by, created_at)
SELECT 1, 10, 20, 40, 70, 100, 30, 'system', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM incident_ladder_configs);
