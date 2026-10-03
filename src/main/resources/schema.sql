-- 雪崩控制区合成雪况与封闭/解除业务建表脚本（H2 MODE=MySQL，仅用于本地与测试，不连接外部数据库）
-- 所有时间字段均为 UTC 整点或 UTC 时间戳；长度单位为厘米。

CREATE TABLE IF NOT EXISTS region_state (
    region_id   VARCHAR(64)  NOT NULL COMMENT '控制区标识',
    status      VARCHAR(16)  NOT NULL COMMENT '当前状态：OPEN=开放，CLOSED=封闭',
    updated_at  TIMESTAMP    NOT NULL COMMENT '状态最后变更时间（UTC）',
    PRIMARY KEY (region_id)
) COMMENT='控制区当前状态，一行一区，用于状态查询与并发行锁';

CREATE TABLE IF NOT EXISTS snow_reading (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '读数主键',
    region_id    VARCHAR(64) NOT NULL COMMENT '控制区标识',
    hour_utc     TIMESTAMP   NOT NULL COMMENT '观测整点（UTC，分钟秒必须为0）',
    depth_cm     INT         NOT NULL COMMENT '雪深，单位厘米',
    new_snow_cm  INT         NOT NULL COMMENT '新增积雪，单位厘米；>5 视为超限',
    request_id   VARCHAR(64) NOT NULL COMMENT '上报幂等键，同参重放、异参冲突',
    created_at   TIMESTAMP   NOT NULL COMMENT '入库时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_reading_region_hour (region_id, hour_utc),
    UNIQUE KEY uk_reading_region_req (region_id, request_id)
) COMMENT='逐小时合成雪况读数；写入后不可改，仅可追加勘误';

CREATE TABLE IF NOT EXISTS reading_errata (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '勘误主键',
    reading_id  BIGINT       NOT NULL COMMENT '关联的雪况读数主键',
    reviewer    VARCHAR(64)  NOT NULL COMMENT '复核人标识',
    note        VARCHAR(500) NOT NULL COMMENT '勘误与复核结论说明',
    request_id  VARCHAR(64)  NOT NULL COMMENT '幂等键',
    created_at  TIMESTAMP    NOT NULL COMMENT '入库时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_errata_req (request_id)
) COMMENT='旧读数的勘误与复核记录，只追加不修改原读数';

CREATE TABLE IF NOT EXISTS closure_command (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '封闭命令主键',
    region_id   VARCHAR(64)  NOT NULL COMMENT '控制区标识',
    source      VARCHAR(16)  NOT NULL COMMENT '来源：MANUAL=人工下发，AUTO=超限读数自动触发',
    reason      VARCHAR(255) NOT NULL COMMENT '封闭原因说明',
    request_id  VARCHAR(64)  NULL COMMENT '人工下发的幂等键；自动触发为空',
    created_at  TIMESTAMP    NOT NULL COMMENT '命令生成时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_closure_req (request_id)
) COMMENT='封闭命令时间线；解除成功后新的超限读数会生成新的封闭命令，不删除历史解除决定';

CREATE TABLE IF NOT EXISTS unseal_review (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '解除审核主键',
    region_id          VARCHAR(64)  NOT NULL COMMENT '控制区标识',
    status             VARCHAR(16)  NOT NULL COMMENT 'PENDING=待双人确认，COMPLETED=已完成，INVALIDATED=已失效',
    window_start       TIMESTAMP    NOT NULL COMMENT '三小时窗口起始整点（UTC，含）',
    window_end         TIMESTAMP    NOT NULL COMMENT '三小时窗口结束整点（UTC，含）',
    reading_ids        VARCHAR(128) NOT NULL COMMENT '窗口内三条读数主键快照，逗号分隔，保证一致读数版本',
    first_reviewer     VARCHAR(64)  NULL COMMENT '第一名确认审核员；未确认为空',
    request_id         VARCHAR(64)  NOT NULL COMMENT '发起审核的幂等键',
    invalidated_reason VARCHAR(255) NULL COMMENT '失效原因；未失效为空',
    created_at         TIMESTAMP    NOT NULL COMMENT '审核发起时间（UTC）',
    completed_at       TIMESTAMP    NULL COMMENT '第二人确认完成时间（UTC）；未完成为空',
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_req (request_id)
) COMMENT='解除封闭双人审核；新的超限读数到来时未完成审核失效';

CREATE TABLE IF NOT EXISTS unseal_confirmation (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '确认记录主键',
    review_id   BIGINT      NOT NULL COMMENT '关联的解除审核主键',
    reviewer    VARCHAR(64) NOT NULL COMMENT '审核员标识，两次确认必须不同人',
    seq         INT         NOT NULL COMMENT '确认次序：1=第一人，2=第二人',
    request_id  VARCHAR(64) NOT NULL COMMENT '幂等键',
    created_at  TIMESTAMP   NOT NULL COMMENT '确认时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_confirm_req (request_id)
) COMMENT='解除审核的逐次确认记录';

CREATE TABLE IF NOT EXISTS unseal_decision (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '解除决定主键',
    review_id   BIGINT      NOT NULL COMMENT '关联的解除审核主键，一次审核只产生一个解除版本',
    region_id   VARCHAR(64) NOT NULL COMMENT '控制区标识',
    version     BIGINT      NOT NULL COMMENT '区域内递增的解除版本号',
    created_at  TIMESTAMP   NOT NULL COMMENT '决定生成时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_decision_review (review_id),
    UNIQUE KEY uk_decision_region_ver (region_id, version)
) COMMENT='解除封闭决定版本；历史决定永不删除';