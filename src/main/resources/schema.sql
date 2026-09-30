-- 野外标签流转业务表结构。
-- 本地与标准测试使用 H2 内存库（MODE=MySQL）自动执行；时间列以 DATETIME(6)
-- 存储 UTC 时刻，应用层统一使用 java.time.Instant 绑定。

CREATE TABLE IF NOT EXISTS site (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    site_code   VARCHAR(64)  NOT NULL COMMENT '站点合成编号',
    name        VARCHAR(128) NOT NULL COMMENT '站点名称（不含真实位置数据）',
    created_at  DATETIME(6)  NOT NULL COMMENT '创建时刻（UTC）',
    CONSTRAINT uk_site_code UNIQUE (site_code)
);

CREATE TABLE IF NOT EXISTS animal (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    animal_code VARCHAR(64)  NOT NULL COMMENT '动物合成编号',
    species     VARCHAR(64)      NULL COMMENT '物种；NULL 表示未登记物种',
    created_at  DATETIME(6)  NOT NULL COMMENT '创建时刻（UTC）',
    CONSTRAINT uk_animal_code UNIQUE (animal_code)
);

CREATE TABLE IF NOT EXISTS tag (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    tag_code       VARCHAR(64)  NOT NULL COMMENT '标签业务编号',
    holder_site_id BIGINT       NOT NULL COMMENT '当前持有站点；交接确认后原子更新为接收站点',
    registered_at  DATETIME(6)  NOT NULL COMMENT '登记时刻（UTC）',
    CONSTRAINT uk_tag_code UNIQUE (tag_code),
    CONSTRAINT fk_tag_holder_site FOREIGN KEY (holder_site_id) REFERENCES site (id)
);

-- 标签附着区间（左闭右开 [attach_at, detach_at)）。
-- 生成列 + 唯一索引：同一标签、同一动物至多存在一条未摘除（detach_at 为 NULL）区间。
CREATE TABLE IF NOT EXISTS attachment (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    animal_id      BIGINT      NOT NULL COMMENT '动物主键',
    tag_id         BIGINT      NOT NULL COMMENT '标签主键',
    site_id        BIGINT      NOT NULL COMMENT '执行附着的站点主键',
    attach_at      DATETIME(6) NOT NULL COMMENT '附着起始时刻（UTC，区间含）',
    detach_at      DATETIME(6)     NULL COMMENT '摘除时刻（UTC，区间不含）；NULL=当前有效',
    detach_site_id BIGINT          NULL COMMENT '摘除操作站点；NULL=尚未摘除',
    open_tag_id    BIGINT GENERATED ALWAYS AS (CASE WHEN detach_at IS NULL THEN tag_id ELSE NULL END),
    open_animal_id BIGINT GENERATED ALWAYS AS (CASE WHEN detach_at IS NULL THEN animal_id ELSE NULL END),
    CONSTRAINT fk_attachment_animal FOREIGN KEY (animal_id) REFERENCES animal (id),
    CONSTRAINT fk_attachment_tag FOREIGN KEY (tag_id) REFERENCES tag (id),
    CONSTRAINT fk_attachment_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_detach_site FOREIGN KEY (detach_site_id) REFERENCES site (id),
    CONSTRAINT uk_attachment_open_tag UNIQUE (open_tag_id),
    CONSTRAINT uk_attachment_open_animal UNIQUE (open_animal_id)
);

CREATE INDEX IF NOT EXISTS idx_attachment_tag ON attachment (tag_id, attach_at);
CREATE INDEX IF NOT EXISTS idx_attachment_animal ON attachment (animal_id, attach_at);

CREATE TABLE IF NOT EXISTS handoff (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    handoff_code  VARCHAR(64)     NOT NULL COMMENT '交接单业务编号',
    tag_id        BIGINT          NOT NULL COMMENT '交接标签主键',
    from_site_id  BIGINT          NOT NULL COMMENT '发起（当前持有）站点主键',
    to_site_id    BIGINT          NOT NULL COMMENT '目标接收站点主键',
    status        VARCHAR(16)     NOT NULL COMMENT '状态：PENDING/CONFIRMED/CANCELLED/REJECTED',
    initiated_at  DATETIME(6)     NOT NULL COMMENT '发起时刻（UTC）',
    confirmed_at  DATETIME(6)         NULL COMMENT '确认时刻（UTC）；仅 CONFIRMED 非空',
    closed_at     DATETIME(6)         NULL COMMENT '取消/拒绝时刻（UTC）；PENDING/CONFIRMED 为 NULL',
    reject_reason VARCHAR(200)          NULL COMMENT '拒绝原因；仅 REJECTED 非空',
    CONSTRAINT uk_handoff_code UNIQUE (handoff_code),
    CONSTRAINT fk_handoff_tag FOREIGN KEY (tag_id) REFERENCES tag (id),
    CONSTRAINT fk_handoff_from_site FOREIGN KEY (from_site_id) REFERENCES site (id),
    CONSTRAINT fk_handoff_to_site FOREIGN KEY (to_site_id) REFERENCES site (id)
);

CREATE INDEX IF NOT EXISTS idx_handoff_tag ON handoff (tag_id, initiated_at);

-- 幂等请求键：附着与交接发起使用；事务失败时不写入即不消费请求键。
CREATE TABLE IF NOT EXISTS idempotent_request (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id   VARCHAR(80)  NOT NULL COMMENT '客户端幂等键 requestId',
    request_type VARCHAR(32)  NOT NULL COMMENT '请求类型：ATTACH/HANDOFF_INITIATE',
    request_hash VARCHAR(200) NOT NULL COMMENT '首次请求参数规范化摘要',
    result_id    BIGINT       NOT NULL COMMENT '首次成功请求产生的业务记录主键',
    created_at   DATETIME(6)  NOT NULL COMMENT '首次成功落库时刻（UTC）',
    CONSTRAINT uk_idempotent_key UNIQUE (request_id, request_type)
);
