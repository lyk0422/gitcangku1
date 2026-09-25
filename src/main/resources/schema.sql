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
    version BIGINT NOT NULL DEFAULT 1 COMMENT '核销版本：批准即版本 1 的核销记录，每次批准或撤销计量更正后递增',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_allocation_held CHECK (held_amount >= 0)
) COMMENT='配水申请；APPROVED 申请即核销记录，持有额度可经计量更正突破原申请水量，由窗口储备约束兜底';

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

CREATE TABLE IF NOT EXISTS meter_correction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '计量更正申请主键',
    meter_key VARCHAR(128) NOT NULL COMMENT '更正业务键（幂等指纹键），全局唯一；指纹含原核销版本、校正数、读表时刻、原因和操作者',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被更正的核销记录（APPROVED 配水申请）业务键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    base_version BIGINT NOT NULL COMMENT '原核销版本，批准时须与核销记录当前版本一致，否则 409',
    previous_amount DECIMAL(19,3) NULL COMMENT '批准时记录的原持有额度，单位立方米，撤销时恢复；未批准为 NULL',
    corrected_amount DECIMAL(19,3) NOT NULL COMMENT '校正数量，单位立方米，大于等于 0，最多 3 位小数',
    reading_nanos BIGINT NOT NULL COMMENT 'UTC 读表时刻，纳秒时间戳',
    reason VARCHAR(512) NOT NULL COMMENT '更正原因',
    actor VARCHAR(128) NOT NULL COMMENT '操作者（X-Actor-Id）',
    status VARCHAR(16) NOT NULL COMMENT '状态：REQUESTED 已登记 / APPROVED 已批准 / REVOKED 已撤销',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_meter_correction_key UNIQUE (meter_key),
    CONSTRAINT fk_meter_correction_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='配水计量更正申请；窗口关闭后只能登记不能批准；批准与撤销均生成反向流水并重算最终态';

CREATE TABLE IF NOT EXISTS meter_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '读表快照主键',
    meter_key VARCHAR(128) NOT NULL COMMENT '对应计量更正业务键',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被更正的核销记录业务键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    corrected_amount DECIMAL(19,3) NOT NULL COMMENT '快照校正数量，单位立方米',
    reading_nanos BIGINT NOT NULL COMMENT 'UTC 读表时刻，纳秒时间戳',
    reason VARCHAR(512) NOT NULL COMMENT '更正原因',
    actor VARCHAR(128) NOT NULL COMMENT '操作者',
    created_nanos BIGINT NOT NULL COMMENT '快照创建时间，UTC 纳秒时间戳',
    CONSTRAINT fk_meter_snapshot_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='不可变读表快照，批准更正时创建，创建后不可修改或删除';

CREATE TABLE IF NOT EXISTS allocation_ledger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '流水主键，即余额演算序号',
    allocation_key VARCHAR(128) NOT NULL COMMENT '所属核销记录（配水申请）业务键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    entry_type VARCHAR(16) NOT NULL COMMENT '类型：WRITE_OFF 原核销 / TRANSFER_OUT 转出 / CANCEL 取消 / CORRECTION 更正反向流水 / REVERSAL 撤销反向流水',
    meter_key VARCHAR(128) NULL COMMENT '对应计量更正业务键；非更正类流水为 NULL',
    delta DECIMAL(19,3) NOT NULL COMMENT '持有额度变化量，单位立方米，可正可负',
    balance_after DECIMAL(19,3) NOT NULL COMMENT '本流水入账后的持有额度，单位立方米',
    created_nanos BIGINT NOT NULL COMMENT '入账时间，UTC 纳秒时间戳',
    CONSTRAINT fk_allocation_ledger_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='额度流水账，创建后不可变；按主键升序即余额演算过程';

CREATE TABLE IF NOT EXISTS command_log (
    command_key VARCHAR(128) PRIMARY KEY COMMENT '命令幂等键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/CORRECTION_SUBMIT/CORRECTION_APPROVE/CORRECTION_REVOKE',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';
