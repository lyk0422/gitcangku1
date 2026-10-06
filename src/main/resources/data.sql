-- 本地入口合成演示数据（H2 内存库，MODE=MySQL；MERGE 保证重复初始化幂等）
-- 场景：两个衔接航段 + 三件待装行李，可直接演示入队/出队/撤队/封舱流程。

MERGE INTO leg (leg_id, origin, destination, status, version, queue_version) KEY(leg_id) VALUES
    ('LEG-DEMO-1', 'PEK', 'SHA', 'OPEN', 1, 1),
    ('LEG-DEMO-2', 'SHA', 'CAN', 'OPEN', 1, 1);

MERGE INTO bag (bag_tag, current_location, next_leg_index, status) KEY(bag_tag) VALUES
    ('BAG-DEMO-A', 'PEK', 0, 'IN_TRANSIT'),
    ('BAG-DEMO-B', 'PEK', 0, 'IN_TRANSIT'),
    ('BAG-DEMO-C', 'PEK', 0, 'IN_TRANSIT');

MERGE INTO bag_itinerary (bag_tag, seq, leg_id, origin, destination) KEY(bag_tag, seq) VALUES
    ('BAG-DEMO-A', 0, 'LEG-DEMO-1', 'PEK', 'SHA'),
    ('BAG-DEMO-B', 0, 'LEG-DEMO-1', 'PEK', 'SHA'),
    ('BAG-DEMO-C', 0, 'LEG-DEMO-1', 'PEK', 'SHA');
