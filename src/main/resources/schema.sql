-- 禁飞区域与航线版本审查：H2（MODE=MySQL）自动建表脚本
-- 仅二维平面模拟，坐标单位为整数米，范围 [-100000,100000]

-- 全局空域版本单行表：任一次禁飞区创建/撤销使 global_version 加一
CREATE TABLE IF NOT EXISTS airspace_meta (
    id             INT PRIMARY KEY,
    global_version INT NOT NULL
);
MERGE INTO airspace_meta (id, global_version) KEY(id) VALUES (1, 0);

-- 禁飞区：非退化轴对齐闭矩形；只能创建或撤销，撤销后不可重建
CREATE TABLE IF NOT EXISTS no_fly_zone (
    zone_id                VARCHAR(128) PRIMARY KEY COMMENT '禁飞区唯一标识',
    x_min                  INT NOT NULL COMMENT '闭矩形西边界x（米）',
    y_min                  INT NOT NULL COMMENT '闭矩形南边界y（米）',
    x_max                  INT NOT NULL COMMENT '闭矩形东边界x（米），x_max>x_min',
    y_max                  INT NOT NULL COMMENT '闭矩形北边界y（米），y_max>y_min',
    active                 BOOLEAN NOT NULL COMMENT '当前是否有效：false=已撤销',
    created_version        INT NOT NULL COMMENT '创建生效时的全局空域版本',
    revoked_version        INT NULL COMMENT '撤销生效时的全局空域版本；NULL 表示仍有效',
    created_at             TIMESTAMP NOT NULL
);

-- 航线当前版本
CREATE TABLE IF NOT EXISTS route (
    route_id    VARCHAR(128) PRIMARY KEY COMMENT '航线唯一标识',
    version     INT NOT NULL COMMENT '航线当前版本，从1开始，替换点列后加一',
    updated_at  TIMESTAMP NOT NULL
);

-- 航线点列（保留全部历史版本，供不可变审核结论复核）
CREATE TABLE IF NOT EXISTS route_point (
    route_id  VARCHAR(128) NOT NULL COMMENT '航线唯一标识',
    version   INT NOT NULL COMMENT '所属航线版本',
    seq       INT NOT NULL COMMENT '点在该版本中的顺序，从0开始',
    x         INT NOT NULL COMMENT '点x坐标（米）',
    y         INT NOT NULL COMMENT '点y坐标（米）',
    PRIMARY KEY (route_id, version, seq)
);

-- 审核结果（不可变）
CREATE TABLE IF NOT EXISTS route_review (
    review_id         VARCHAR(64) PRIMARY KEY COMMENT '审核记录唯一标识',
    route_id          VARCHAR(128) NOT NULL COMMENT '被审核航线',
    route_version     INT NOT NULL COMMENT '提交审核时指定的航线版本',
    airspace_version  INT NOT NULL COMMENT '提交审核时指定的全局空域版本',
    conclusion        VARCHAR(16) NOT NULL COMMENT '原结论：CLEAR/BLOCKED，永不改变',
    hit_zone_ids      VARCHAR(2000) NOT NULL COMMENT '命中禁飞区zoneId，字典序去重，逗号分隔；无命中为空串',
    created_at        TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_review_route ON route_review (route_id, created_at);

-- 写操作幂等记录（仅记录成功结果，失败不占键）
CREATE TABLE IF NOT EXISTS request_record (
    request_id     VARCHAR(128) PRIMARY KEY COMMENT '全局唯一请求标识',
    operation      VARCHAR(32) NOT NULL COMMENT '操作类型：ZONE_CREATE/ZONE_REVOKE/ROUTE_CREATE/ROUTE_REPLACE/REVIEW',
    fingerprint    VARCHAR(64) NOT NULL COMMENT '请求参数指纹，同键异参用于冲突检测',
    status_code    INT NOT NULL COMMENT '原成功响应HTTP状态码',
    response_body  CLOB NOT NULL COMMENT '原成功响应体JSON，重放原样返回',
    created_at     TIMESTAMP NOT NULL
);
