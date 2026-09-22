-- 禁飞区域与航线版本审查：H2（MODE=MySQL）自动建表脚本。
-- 数据仅保留在 JVM 生命周期内，不要求跨进程恢复。

-- 全局空域版本：单行表，任一禁飞区创建/撤销使其加一；审核与禁飞区变更通过该行排他锁保证一致快照。
CREATE TABLE IF NOT EXISTS airspace_state (
    id TINYINT PRIMARY KEY COMMENT '固定为 1 的单行标识',
    version BIGINT NOT NULL COMMENT '全局空域版本号，从 0 开始，禁飞区每次变更加一'
);
COMMENT ON TABLE airspace_state IS '全局空域版本单行表';

INSERT INTO airspace_state (id, version)
SELECT 1, 0 WHERE NOT EXISTS (SELECT 1 FROM airspace_state);

-- 禁飞区：zoneId 唯一，非退化轴对齐闭矩形，只能创建或撤销；撤销保留行用于历史审核结果解释。
CREATE TABLE IF NOT EXISTS zones (
    zone_id VARCHAR(64) PRIMARY KEY COMMENT '禁飞区唯一标识，创建后不可复用',
    min_x INT NOT NULL COMMENT '矩形最小 X（米，闭区间，[-100000,100000]）',
    min_y INT NOT NULL COMMENT '矩形最小 Y（米，闭区间，[-100000,100000]）',
    max_x INT NOT NULL COMMENT '矩形最大 X（米，闭区间，须大于 min_x）',
    max_y INT NOT NULL COMMENT '矩形最大 Y（米，闭区间，须大于 min_y）',
    active BOOLEAN NOT NULL COMMENT '是否有效；FALSE 表示已撤销，不参与后续审核',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（应用时钟，Asia/Shanghai）',
    revoked_at TIMESTAMP NULL COMMENT '撤销时间；NULL 表示未撤销'
);
COMMENT ON TABLE zones IS '禁飞区表，仅支持创建与撤销';

-- 航线：routeId 唯一，含 2~50 个按顺序连接的点；替换点列使版本加一并使当前审核失效。
CREATE TABLE IF NOT EXISTS routes (
    route_id VARCHAR(64) PRIMARY KEY COMMENT '航线唯一标识',
    version INT NOT NULL COMMENT '航线版本号，创建为 1，每次成功替换加一',
    points TEXT NOT NULL COMMENT '有序点列 JSON：[{"x":..,"y":..},...]，坐标单位米',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（应用时钟）',
    updated_at TIMESTAMP NOT NULL COMMENT '最近一次替换时间（应用时钟）'
);
COMMENT ON TABLE routes IS '航线表，点列以 JSON 保存';

-- 审核结果：不可变历史记录，结论与命中区域以提交时的一致快照计算并永久保留。
CREATE TABLE IF NOT EXISTS reviews (
    review_id VARCHAR(36) PRIMARY KEY COMMENT '审核结果唯一标识（UUID）',
    route_id VARCHAR(64) NOT NULL COMMENT '被审核航线标识',
    route_version INT NOT NULL COMMENT '审核时航线版本',
    airspace_version BIGINT NOT NULL COMMENT '审核时全局空域版本',
    conclusion VARCHAR(8) NOT NULL COMMENT '结论：CLEAR 或 BLOCKED',
    hit_zone_ids TEXT NOT NULL COMMENT '命中的禁飞区 zoneId，按字典序去重后的 JSON 数组；CLEAR 时为 []',
    request_id VARCHAR(64) NOT NULL COMMENT '提交本次审核的幂等请求标识',
    created_at TIMESTAMP NOT NULL COMMENT '审核完成时间（应用时钟）'
);
COMMENT ON TABLE reviews IS '审核结果表，记录不可变';

-- 幂等键：所有写操作携带全局唯一 requestId；同键同参重放原成功结果，异参返回 409；失败不占键。
CREATE TABLE IF NOT EXISTS idempotency_keys (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '全局唯一幂等请求标识',
    action VARCHAR(32) NOT NULL COMMENT '写操作类型，如 CREATE_ZONE/REVOKE_ZONE/CREATE_ROUTE/REPLACE_ROUTE/REVIEW',
    fingerprint VARCHAR(512) NOT NULL COMMENT '请求参数指纹，用于同键异参检测',
    response_body TEXT NULL COMMENT '成功响应 JSON；事务提交前写入，失败回滚即不占键',
    created_at TIMESTAMP NOT NULL COMMENT '键创建时间（应用时钟）'
);
COMMENT ON TABLE idempotency_keys IS '写操作幂等去重表';
