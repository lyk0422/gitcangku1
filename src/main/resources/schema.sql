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
    held_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '当前持有额度，单位立方米；批准时等于原申请水量，取消时归零，转让与核销时等额扣减',
    credit_coefficient DECIMAL(19,6) NULL COMMENT '降雨抵扣系数，单位立方米/毫米，申请每毫米有效降雨可声明的用水抵扣；NULL 表示未声明（不参与抵扣），核销时固化快照',
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
    operation VARCHAR(48) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/RAIN_REGISTER/RAIN_CONFIRM/RAIN_REVOKE/CREDIT_FACTOR/WRITE_OFF/WRITE_OFF_BATCH',
    params VARCHAR(4096) NOT NULL COMMENT '规范化请求参数串（JSON 规范化指纹），用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

CREATE TABLE IF NOT EXISTS rainfall_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '降雨记录主键',
    rain_key VARCHAR(128) NOT NULL COMMENT '降雨记录业务键，全局唯一',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID；窗口区间按左闭右开解释',
    rainfall_mm DECIMAL(19,3) NOT NULL COMMENT '经复核的有效降雨量，单位毫米，大于 0，最多 3 位小数',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING 待两名不同水务角色确认 / EFFECTIVE 已生效 / REVOKED 已撤销',
    registrar VARCHAR(128) NOT NULL COMMENT '登记操作人（X-Actor-Id）',
    confirm_count INT NOT NULL DEFAULT 0 COMMENT '已收到的不同水务角色确认人数，达到 2 人时记录生效',
    created_nanos BIGINT NOT NULL COMMENT '登记时间，UTC 纳秒时间戳',
    effective_nanos BIGINT NULL COMMENT '生效时间（第二名不同角色确认时刻），UTC 纳秒时间戳；未生效为 NULL',
    revoked_nanos BIGINT NULL COMMENT '撤销时间，UTC 纳秒时间戳；未撤销为 NULL',
    revoked_by VARCHAR(128) NULL COMMENT '撤销操作人（X-Actor-Id）；未撤销为 NULL',
    CONSTRAINT uk_rainfall_key UNIQUE (rain_key),
    CONSTRAINT fk_rainfall_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_rainfall_amount CHECK (rainfall_mm > 0)
) COMMENT='灌区窗口降雨记录，两名不同水务角色确认后生效；撤销不影响已固化到核销的业务证据';

CREATE TABLE IF NOT EXISTS rainfall_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '降雨确认主键',
    rain_id BIGINT NOT NULL COMMENT '所属降雨记录主键',
    role_id VARCHAR(128) NOT NULL COMMENT '水务角色 ID，同一降雨记录每个角色至多确认一次，须两名不同角色',
    confirmer VARCHAR(128) NOT NULL COMMENT '确认操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '确认时间，UTC 纳秒时间戳',
    CONSTRAINT uk_rainfall_role UNIQUE (rain_id, role_id),
    CONSTRAINT fk_confirmation_rain FOREIGN KEY (rain_id) REFERENCES rainfall_record (id)
) COMMENT='降雨确认明细，按 (降雨记录, 角色) 去重，两名不同角色确认使降雨记录生效';

CREATE TABLE IF NOT EXISTS write_off (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '核销主键',
    write_off_key VARCHAR(128) NOT NULL COMMENT '核销业务键，全局唯一（单笔与批量共用）',
    batch_key VARCHAR(128) NULL COMMENT '所属批量核销业务键；单笔核销为 NULL',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    allocation_key VARCHAR(128) NOT NULL COMMENT '配水申请业务键（核销时快照固化）',
    requested_amount DECIMAL(19,3) NOT NULL COMMENT '本次请求核销量，单位立方米，大于 0',
    rainfall_mm DECIMAL(19,3) NOT NULL COMMENT '核销时固化的有效降雨量，单位毫米；无有效降雨为 0',
    coefficient DECIMAL(19,6) NOT NULL COMMENT '核销时固化的申请抵扣系数，单位立方米/毫米；未声明为 0',
    credit_amount DECIMAL(19,3) NOT NULL COMMENT '降雨抵扣量，单位立方米，先于配额扣减计算且不超过本次核销量',
    quota_deduction DECIMAL(19,3) NOT NULL COMMENT '配额实际扣减量，单位立方米；配额扣减加抵扣必须等于原核销量',
    held_before DECIMAL(19,3) NOT NULL COMMENT '核销前持有额度快照，单位立方米',
    held_after DECIMAL(19,3) NOT NULL COMMENT '核销后持有额度快照，单位立方米',
    actor VARCHAR(128) NOT NULL COMMENT '核销操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '核销时间，UTC 纳秒时间戳',
    CONSTRAINT uk_write_off_key UNIQUE (write_off_key),
    CONSTRAINT fk_write_off_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_write_off_conservation CHECK (credit_amount >= 0 AND quota_deduction >= 0
        AND credit_amount <= requested_amount
        AND credit_amount + quota_deduction = requested_amount)
) COMMENT='配水核销流水，先降雨抵扣再扣减配额，固化降雨记录、系数、抵扣与实际扣减，创建后不可变';

CREATE TABLE IF NOT EXISTS write_off_rain (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '核销-降雨固化关联主键',
    write_off_id BIGINT NOT NULL COMMENT '所属核销主键',
    rain_key VARCHAR(128) NOT NULL COMMENT '固化的降雨记录业务键（即使记录事后撤销也不改写）',
    rainfall_mm DECIMAL(19,3) NOT NULL COMMENT '固化时的有效降雨量快照，单位毫米',
    created_nanos BIGINT NOT NULL COMMENT '关联写入时间，UTC 纳秒时间戳',
    CONSTRAINT uk_write_off_rain UNIQUE (write_off_id, rain_key),
    CONSTRAINT fk_write_off_rain_write_off FOREIGN KEY (write_off_id) REFERENCES write_off (id)
) COMMENT='核销所依据的降雨记录快照明细，按核销主键、降雨业务键去重，创建后不可变';
