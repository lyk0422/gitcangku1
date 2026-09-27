-- 灌区配水配额与限供 schema。时间统一为 UTC 纳秒时间戳（BIGINT），水量单位为立方米，DECIMAL(19,3) 精确到 0.001。
CREATE TABLE IF NOT EXISTS supply_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '供水窗口主键',
    window_key VARCHAR(128) NOT NULL COMMENT '窗口业务键，全局唯一',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID，同一渠道窗口不得重叠',
    start_nanos BIGINT NOT NULL COMMENT '窗口开始时刻，UTC 纳秒时间戳',
    end_nanos BIGINT NOT NULL COMMENT '窗口结束时刻（不含），UTC 纳秒时间戳',
    planned_volume DECIMAL(19,3) NOT NULL COMMENT '计划水量，单位立方米，最多 3 位小数',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_supply_window_key UNIQUE (window_key)
) COMMENT='供水窗口，创建后不可修改';

CREATE TABLE IF NOT EXISTS curtailment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '限供记录主键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    volume DECIMAL(19,3) NOT NULL COMMENT '限供水量，单位立方米，大于 0 且不超过计划水量',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 生效中 / CANCELLED 已取消',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    cancelled_nanos BIGINT NULL COMMENT '取消时间，UTC 纳秒时间戳；未取消为 NULL',
    CONSTRAINT fk_curtailment_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='窗口限供，同一窗口至多一条 ACTIVE';

CREATE TABLE IF NOT EXISTS allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '配水申请主键',
    allocation_key VARCHAR(128) NOT NULL COMMENT '申请业务键，全局唯一',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    user_id VARCHAR(128) NOT NULL COMMENT '用水户 ID',
    amount DECIMAL(19,3) NOT NULL COMMENT '原申请水量，单位立方米，最多 3 位小数，创建后不可改写',
    held_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '当前持有额度，单位立方米；批准时等于原申请水量，取消时归零，转出时等额扣减',
    requester VARCHAR(128) NOT NULL COMMENT '申请人（X-Actor-Id）',
    status VARCHAR(16) NOT NULL COMMENT '状态：REQUESTED 已申请 / APPROVED 已批准 / CANCELLED 已取消（不可恢复）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_allocation_held CHECK (held_amount >= 0 AND held_amount <= amount)
) COMMENT='配水申请';

CREATE TABLE IF NOT EXISTS transfer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '转让流水主键',
    transfer_key VARCHAR(128) NOT NULL COMMENT '转让业务键，全局唯一；换 commandKey 复用返回 409',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID（源与目标必须同窗口）',
    source_allocation_key VARCHAR(128) NOT NULL COMMENT '转出申请业务键（APPROVED，操作人须为申请人本人）',
    target_allocation_key VARCHAR(128) NOT NULL COMMENT '转入申请业务键（REQUESTED，不同用水户）',
    amount DECIMAL(19,3) NOT NULL COMMENT '转让额度，单位立方米，等于目标原申请水量的全部额度',
    actor VARCHAR(128) NOT NULL COMMENT '操作人（源申请人，X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_transfer_key UNIQUE (transfer_key),
    CONSTRAINT fk_transfer_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='配水额度转让流水，与源扣减、目标批准同事务提交，创建后不可变，不提供撤销';

CREATE TABLE IF NOT EXISTS command_log (
    command_key VARCHAR(128) PRIMARY KEY COMMENT '命令幂等键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/SENSOR_RULE_CONFIGURE/READING_SUBMIT/ANOMALY_RESOLVE/WRITE_OFF',
    params TEXT NOT NULL COMMENT '规范化请求指纹（结构化 JSON，包含操作者/操作类型/规范化对象集合/全部影响结果字段），用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

-- ===== 配水计量传感异常与核销持续拦截（本题新增） =====

CREATE TABLE IF NOT EXISTS channel (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '渠道主键',
    channel_key VARCHAR(128) NOT NULL COMMENT '渠道业务 ID，全局唯一；同渠道读数/核销/冻结解除事务通过锁定本行串行裁决',
    created_nanos BIGINT NOT NULL COMMENT '渠道首次配置时间，UTC 纳秒时间戳',
    CONSTRAINT uk_channel_key UNIQUE (channel_key)
) COMMENT='渠道锚点行，首次配置传感规则时创建，创建后不可变';

CREATE TABLE IF NOT EXISTS sensor_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '规则版本主键',
    channel_id VARCHAR(128) NOT NULL COMMENT '所属渠道 ID',
    rule_version INT NOT NULL COMMENT '规则版本号，渠道内从 1 递增，读数按提交时刻锁定的最新版本裁决',
    max_flow_rate DECIMAL(19,6) NOT NULL COMMENT '瞬时流量上限，单位立方米/分钟，大于 0；实际值=相邻读数增量/间隔分钟',
    max_gap_minutes INT NOT NULL COMMENT '相邻读数最大允许间隔，单位分钟，大于 0',
    actor VARCHAR(128) NOT NULL COMMENT '配置操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '版本提交时间，UTC 纳秒时间戳',
    CONSTRAINT uk_sensor_rule_version UNIQUE (channel_id, rule_version)
) COMMENT='传感异常规则版本，提交后不可变；新读数只按提交时最新版本裁决';

CREATE TABLE IF NOT EXISTS sensor_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '读数主键',
    batch_key VARCHAR(128) NOT NULL COMMENT '提交批次业务键，全局唯一，换 commandKey 复用返回 409',
    channel_id VARCHAR(128) NOT NULL COMMENT '所属渠道 ID',
    seq_no INT NOT NULL COMMENT '批内序号，从 0 开始，与批次键共同定位读数',
    reading_nanos BIGINT NOT NULL COMMENT '读数时刻，UTC 纳秒时间戳；同渠道必须严格递增，相等或倒退返回 422',
    cumulative DECIMAL(19,3) NOT NULL COMMENT '累计表读数，单位立方米，非负，读数写入后不可变',
    rule_version INT NOT NULL COMMENT '提交时渠道最新规则版本号，作为业务证据固定',
    actor VARCHAR(128) NOT NULL COMMENT '提交操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '批次提交时间，UTC 纳秒时间戳',
    CONSTRAINT uk_sensor_reading_batch_seq UNIQUE (batch_key, seq_no),
    CONSTRAINT uk_sensor_reading_channel_time UNIQUE (channel_id, reading_nanos),
    CONSTRAINT chk_sensor_reading_cumulative CHECK (cumulative >= 0)
) COMMENT='渠道计量点累计读数，批次整体校验通过后在同一事务写入，写入后不可变';

CREATE TABLE IF NOT EXISTS sensor_anomaly (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '异常主键',
    channel_id VARCHAR(128) NOT NULL COMMENT '所属渠道 ID',
    batch_key VARCHAR(128) NOT NULL COMMENT '触发异常的读数批次业务键',
    rule_version INT NOT NULL COMMENT '裁决所用规则版本号',
    from_reading_id BIGINT NOT NULL COMMENT '相邻对前一读数主键（可能是历史批次末点）',
    to_reading_id BIGINT NOT NULL COMMENT '相邻对后一读数主键（本批读数）',
    from_reading_nanos BIGINT NOT NULL COMMENT '前一读数时刻，UTC 纳秒时间戳',
    to_reading_nanos BIGINT NOT NULL COMMENT '后一读数时刻，UTC 纳秒时间戳',
    from_cumulative DECIMAL(19,3) NOT NULL COMMENT '前一累计读数，单位立方米',
    to_cumulative DECIMAL(19,3) NOT NULL COMMENT '后一累计读数，单位立方米',
    reasons VARCHAR(256) NOT NULL COMMENT '命中原因 JSON 字符串数组：REGRESSION 倒退/FLOW_EXCEEDED 超瞬时流量/GAP_EXCEEDED 超间隔，结构化存储不做裸拼接',
    actual_delta DECIMAL(19,3) NOT NULL COMMENT '实际读数增量（后-前），单位立方米，倒退为负',
    actual_gap_minutes DECIMAL(19,6) NOT NULL COMMENT '实际读数间隔，单位分钟，左闭右开区间差',
    actual_flow_rate DECIMAL(19,6) NOT NULL COMMENT '实际瞬时流量，单位立方米/分钟，等于增量除以间隔分钟',
    max_gap_minutes INT NOT NULL COMMENT '裁决时要求的最大间隔，单位分钟',
    max_flow_rate DECIMAL(19,6) NOT NULL COMMENT '裁决时要求的瞬时流量上限，单位立方米/分钟',
    detector_actor VARCHAR(128) NOT NULL COMMENT '异常证据提交操作人；解除操作人必须与之不同',
    created_nanos BIGINT NOT NULL COMMENT '异常生成（批次提交）时间，UTC 纳秒时间戳'
) COMMENT='传感异常，由相邻读数对命中规则生成，生成后不可变，也不被解除改写';

CREATE TABLE IF NOT EXISTS channel_freeze (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '冻结主键',
    channel_id VARCHAR(128) NOT NULL COMMENT '被冻结渠道 ID',
    anomaly_id BIGINT NOT NULL COMMENT '触发冻结的异常主键，一条异常对应一次冻结',
    frozen_nanos BIGINT NOT NULL COMMENT '冻结生效时间，UTC 纳秒时间戳；与异常同事务提交',
    CONSTRAINT uk_channel_freeze_anomaly UNIQUE (anomaly_id)
) COMMENT='渠道冻结记录，创建后不可变；是否已解除以是否存在 anomaly_resolution 推导，不回写本行';

CREATE TABLE IF NOT EXISTS anomaly_resolution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '解除记录主键',
    anomaly_id BIGINT NOT NULL COMMENT '被解除的异常主键',
    channel_id VARCHAR(128) NOT NULL COMMENT '所属渠道 ID',
    diagnosis_note VARCHAR(1024) NOT NULL COMMENT '诊断说明，解除时由不同操作人确认，非空，写入后不可变',
    replacement_cumulative DECIMAL(19,3) NOT NULL COMMENT '替代累计读数，单位立方米，非负，写入后不可变',
    replacement_nanos BIGINT NOT NULL COMMENT '替代读数时刻，UTC 纳秒时间戳，固定为异常后一读数时刻',
    resolver_actor VARCHAR(128) NOT NULL COMMENT '解除操作人（X-Actor-Id），必须不同于异常提交操作人',
    created_nanos BIGINT NOT NULL COMMENT '解除时间，UTC 纳秒时间戳',
    CONSTRAINT uk_anomaly_resolution_anomaly UNIQUE (anomaly_id)
) COMMENT='异常解除记录，不改写原异常、冻结或既有核销；插入后不可变';

CREATE TABLE IF NOT EXISTS write_off (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '核销主键',
    write_off_key VARCHAR(128) NOT NULL COMMENT '核销业务键，全局唯一，换 commandKey 复用返回 409',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被核销的已批准配水申请业务键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    channel_id VARCHAR(128) NOT NULL COMMENT '核销渠道 ID（取自窗口），冻结期间新核销被拦截',
    amount DECIMAL(19,3) NOT NULL COMMENT '本次核销水量，单位立方米，大于 0 且不超过申请当前持有额度',
    at_nanos BIGINT NOT NULL COMMENT '核销发生时刻，UTC 纳秒时间戳；按提交（事务提交）顺序裁决',
    actor VARCHAR(128) NOT NULL COMMENT '核销操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '核销提交时间，UTC 纳秒时间戳',
    CONSTRAINT uk_write_off_key UNIQUE (write_off_key),
    CONSTRAINT fk_write_off_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_write_off_amount CHECK (amount > 0)
) COMMENT='实际核销流水，与申请持有额度扣减同事务提交，写入后不可变；渠道存在未解除异常时新核销拒绝';
