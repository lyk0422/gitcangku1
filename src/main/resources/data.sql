-- 合成初始化数据（H2 内存库，随 JVM 退出而丢失）
-- 幂等插入，重复初始化不报错；测试用例在 @BeforeEach 中自行清理场景数据。
-- 布局航段 LAYOUT-10：10 x 10 毫米舱底；旧航段 LEGACY-1 无二维配置，验证兼容。

INSERT INTO leg (leg_id, origin, destination, status, version)
SELECT 'LAYOUT-10', 'PEK', 'SHA', 'OPEN', 1
WHERE NOT EXISTS (SELECT 1 FROM leg WHERE leg_id = 'LAYOUT-10');

INSERT INTO floor_layout (leg_id, width_mm, height_mm)
SELECT 'LAYOUT-10', 10, 10
WHERE NOT EXISTS (SELECT 1 FROM floor_layout WHERE leg_id = 'LAYOUT-10');

INSERT INTO leg (leg_id, origin, destination, status, version)
SELECT 'LEGACY-1', 'CAN', 'XIY', 'OPEN', 1
WHERE NOT EXISTS (SELECT 1 FROM leg WHERE leg_id = 'LEGACY-1');

-- 两件 5 x 10 毫米合成行李，可在 10 x 10 舱底左右并排
INSERT INTO bag (bag_tag, current_location, next_leg_index, status)
SELECT 'BAG-LEFT', 'PEK', 0, 'IN_TRANSIT'
WHERE NOT EXISTS (SELECT 1 FROM bag WHERE bag_tag = 'BAG-LEFT');

INSERT INTO bag_size (bag_tag, width_mm, height_mm)
SELECT 'BAG-LEFT', 5, 10
WHERE NOT EXISTS (SELECT 1 FROM bag_size WHERE bag_tag = 'BAG-LEFT');

INSERT INTO bag_itinerary (bag_tag, seq, leg_id, origin, destination)
SELECT 'BAG-LEFT', 0, 'LAYOUT-10', 'PEK', 'SHA'
WHERE NOT EXISTS (SELECT 1 FROM bag_itinerary WHERE bag_tag = 'BAG-LEFT' AND seq = 0);

INSERT INTO bag (bag_tag, current_location, next_leg_index, status)
SELECT 'BAG-RIGHT', 'PEK', 0, 'IN_TRANSIT'
WHERE NOT EXISTS (SELECT 1 FROM bag WHERE bag_tag = 'BAG-RIGHT');

INSERT INTO bag_size (bag_tag, width_mm, height_mm)
SELECT 'BAG-RIGHT', 5, 10
WHERE NOT EXISTS (SELECT 1 FROM bag_size WHERE bag_tag = 'BAG-RIGHT');

INSERT INTO bag_itinerary (bag_tag, seq, leg_id, origin, destination)
SELECT 'BAG-RIGHT', 0, 'LAYOUT-10', 'PEK', 'SHA'
WHERE NOT EXISTS (SELECT 1 FROM bag_itinerary WHERE bag_tag = 'BAG-RIGHT' AND seq = 0);

-- 无尺寸行李，仅供旧航段兼容场景
INSERT INTO bag (bag_tag, current_location, next_leg_index, status)
SELECT 'BAG-LEGACY', 'CAN', 0, 'IN_TRANSIT'
WHERE NOT EXISTS (SELECT 1 FROM bag WHERE bag_tag = 'BAG-LEGACY');

INSERT INTO bag_itinerary (bag_tag, seq, leg_id, origin, destination)
SELECT 'BAG-LEGACY', 0, 'LEGACY-1', 'CAN', 'XIY'
WHERE NOT EXISTS (SELECT 1 FROM bag_itinerary WHERE bag_tag = 'BAG-LEGACY' AND seq = 0);
