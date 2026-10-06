-- 合成演示数据（题干场景）：设备容量100、旧镜像40；发布单压缩包20、解包镜像50。
-- 领取峰值 40+20+50=110 超过容量100，返回422；维护容量到110后可领取，成功后已用50、余量60。
-- 仅本地入口加载；标准 test 使用独立命名库且不加载本文件。

INSERT INTO device (device_id, model, current_version, bucket_no,
                    total_space_bytes, current_image_bytes, space_version)
VALUES ('demo-device-1', 'demo-model', '1.0.0', 1, 100, 40, 1);

INSERT INTO release_order (version, model, from_version, to_version, ratio, status,
                           sample_floor, failure_threshold_percent, monitor_round, active_model,
                           package_bytes, image_bytes)
VALUES (1, 'demo-model', '1.0.0', '2.0.0', 100, 'ACTIVE', 2, 100, 1, 'demo-model', 20, 50);
