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
    credit_coefficient DECIMAL(19,6) NULL COMMENT '每毫米有效降雨对应的用水抵扣系数，单位立方米/毫米；NULL 表示该申请不参与降雨抵扣；不允许为负',
    status VARCHAR(16) NOT NULL COMMENT '状态：REQUESTED 已申请 / APPROVED 已批准 / CANCELLED 已取消（不可恢复）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_allocation_held CHECK (held_amount >= 0 AND held_amount <= amount),
    CONSTRAINT chk_allocation_coef CHECK (credit_coefficient IS NULL OR credit_coefficient >= 0)
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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/RAIN_REGISTER/RAIN_CONFIRM/RAIN_REVOKE/WRITEOFF',
    params VARCHAR(4000) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

CREATE TABLE IF NOT EXISTS rain_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '降雨记录主键',
    rain_key VARCHAR(128) NOT NULL COMMENT '降雨记录业务键，全局唯一',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID；降雨时刻必须落在窗口 [start,end) 内',
    rain_millis DECIMAL(19,3) NOT NULL COMMENT '经复核的有效降雨量，单位毫米，必须大于 0',
    observed_nanos BIGINT NOT NULL COMMENT '降雨观测时刻，UTC 纳秒时间戳，按左闭右开校验属于窗口',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING 待两名水务角色确认 / EFFECTIVE 已生效 / REVOKED 已撤销；撤销不影响已结算核销',
    registered_by VARCHAR(128) NOT NULL COMMENT '登记操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '登记时间，UTC 纳秒时间戳',
    effective_nanos BIGINT NULL COMMENT '生效时间（第二名不同水务角色确认时刻），UTC 纳秒时间戳；未生效为 NULL',
    revoked_nanos BIGINT NULL COMMENT '撤销时间，UTC 纳秒时间戳；未撤销为 NULL',
    revoked_by VARCHAR(128) NULL COMMENT '撤销操作人（X-Actor-Id）；未撤销为 NULL',
    effective_window_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'EFFECTIVE' THEN window_id ELSE NULL END) COMMENT '生效唯一性辅助列：仅 EFFECTIVE 时取窗口 ID，保证同窗口至多一条生效记录',
    CONSTRAINT uk_rain_key UNIQUE (rain_key),
    CONSTRAINT fk_rain_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT uk_rain_effective_window UNIQUE (effective_window_id),
    CONSTRAINT chk_rain_millis CHECK (rain_millis > 0)
) COMMENT='灌区窗口降雨记录，需两名不同水务角色确认才生效，同窗口至多一条 EFFECTIVE；已结算核销冻结其证据';

CREATE TABLE IF NOT EXISTS rain_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '降雨确认主键',
    rain_record_id BIGINT NOT NULL COMMENT '所属降雨记录主键',
    confirmer VARCHAR(128) NOT NULL COMMENT '确认水务角色操作人（X-Actor-Id），同一记录两名确认人必须不同',
    created_nanos BIGINT NOT NULL COMMENT '确认时间，UTC 纳秒时间戳',
    CONSTRAINT uk_rain_confirmer UNIQUE (rain_record_id, confirmer),
    CONSTRAINT fk_confirmation_rain FOREIGN KEY (rain_record_id) REFERENCES rain_record (id)
) COMMENT='降雨确认证据，每条记录每名操作人至多确认一次，两名不同操作人确认后降雨记录生效';

CREATE TABLE IF NOT EXISTS writeoff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '核销单主键',
    writeoff_key VARCHAR(128) NOT NULL COMMENT '核销业务键，全局唯一',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    total_amount DECIMAL(19,3) NOT NULL COMMENT '原核销总量，单位立方米，等于各明细 amount 之和，创建后不可改写',
    total_credit DECIMAL(19,3) NOT NULL COMMENT '降雨抵扣总量，单位立方米，等于各明细 credit 之和，不超过原核销量',
    total_quota DECIMAL(19,3) NOT NULL COMMENT '配额实际扣减总量，单位立方米，满足 total_quota + total_credit = total_amount',
    status VARCHAR(16) NOT NULL COMMENT '状态：SETTLED 已结算（不提供撤销）',
    actor VARCHAR(128) NOT NULL COMMENT '核销操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '核销时间，UTC 纳秒时间戳',
    CONSTRAINT uk_writeoff_key UNIQUE (writeoff_key),
    CONSTRAINT fk_writeoff_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='核销单；先计降雨抵扣再扣配额，固化当时降雨记录/系数/抵扣证据，后续撤销降雨不改写';

CREATE TABLE IF NOT EXISTS writeoff_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '核销明细主键',
    writeoff_id BIGINT NOT NULL COMMENT '所属核销单主键',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被核销申请业务键（结算时必须 APPROVED）',
    seq_no INT NOT NULL COMMENT '明细在批量核销中的序号，从 0 开始，按请求顺序稳定排序',
    amount DECIMAL(19,3) NOT NULL COMMENT '本明细原核销量，单位立方米，必须大于 0',
    credit DECIMAL(19,3) NOT NULL COMMENT '本明细降雨抵扣量，单位立方米，0 <= credit <= amount',
    quota_deduction DECIMAL(19,3) NOT NULL COMMENT '本明细配额实际扣减量，单位立方米，满足 quota_deduction + credit = amount',
    coefficient DECIMAL(19,6) NULL COMMENT '结算时申请的每毫米抵扣系数快照，单位立方米/毫米；不参与抵扣为 NULL',
    applied_rains VARCHAR(2048) NOT NULL COMMENT '结算时参与抵扣的 EFFECTIVE 降雨证据 JSON 数组快照，元素含 rainKey/rainMillis/credit，按 rainKey 排序',
    CONSTRAINT uk_writeoff_item_seq UNIQUE (writeoff_id, seq_no),
    CONSTRAINT fk_item_writeoff FOREIGN KEY (writeoff_id) REFERENCES writeoff (id),
    CONSTRAINT chk_item_conservation CHECK (credit >= 0 AND credit <= amount
            AND quota_deduction >= 0 AND quota_deduction <= amount
            AND credit + quota_deduction = amount)
) COMMENT='核销明细；冻结申请系数与降雨证据集合，按 seq_no 稳定排序，不可变';
