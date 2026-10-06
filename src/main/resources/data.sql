-- 合成演示数据（H2，MODE=MySQL）：容量100、旧镜像40、包20、新镜像50。
-- 用于本地启动后手工验证暂存峰值核验：领取因 40+20+50=110>100 返回422，
-- 维护容量到110后可领取，SUCCESS 后已用50、余量60。测试启动时同样加载，用例执行前会清空。

INSERT INTO device (device_id, model, current_version, bucket_no, total_space_bytes, current_image_bytes, space_version)
SELECT 'demo-device', 'demo-model', '1.0.0', 5, 100, 40, 1
WHERE NOT EXISTS (SELECT 1 FROM device WHERE device_id = 'demo-device');

INSERT INTO release_order (version, model, from_version, to_version, ratio, status,
                           sample_floor, failure_threshold_percent, monitor_round, active_model,
                           package_bytes, image_bytes)
SELECT 1, 'demo-model', '1.0.0', '2.0.0', 100, 'ACTIVE', 2, 100, 1, 'demo-model', 20, 50
WHERE NOT EXISTS (SELECT 1 FROM release_order WHERE model = 'demo-model');
