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
    settled_delivered DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '仍有效核销的累计送达水量，单位立方米，3 位小数；反向更正时等额冲回',
    settled_loss DECIMAL(19,2) NOT NULL DEFAULT 0 COMMENT '仍有效核销的累计蒸发损耗，单位立方米，2 位小数；反向更正时等额冲回',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_allocation_held CHECK (held_amount >= 0 AND held_amount <= amount),
    CONSTRAINT chk_allocation_settled CHECK (settled_delivered >= 0 AND settled_loss >= 0)
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

CREATE TABLE IF NOT EXISTS evaporation_rate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '蒸发率版本主键',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID',
    rate_date DATE NOT NULL COMMENT '适用 UTC 自然日（左闭右开的当日 [00:00, 次日00:00)）',
    rate DECIMAL(6,4) NOT NULL COMMENT '蒸发率，无量纲，0～0.2000，最多 4 位小数',
    version INT NOT NULL COMMENT '该渠道当日版本号，从 1 递增；核销固化的是具体版本',
    superseded TINYINT NOT NULL DEFAULT 0 COMMENT '是否已被新版本取代：0 当前版本 / 1 历史版本（证据保留，不可删除）',
    created_nanos BIGINT NOT NULL COMMENT '版本创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_evaporation_rate_version UNIQUE (channel_id, rate_date, version)
) COMMENT='渠道按 UTC 自然日登记的蒸发率版本；当日首次核销后只允许追加新版本与否决修改，核销固化当时版本';

CREATE TABLE IF NOT EXISTS settlement_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '核销批次主键，单笔核销同样生成批次',
    batch_key VARCHAR(128) NOT NULL COMMENT '批次业务键，全局唯一',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    status VARCHAR(16) NOT NULL COMMENT '状态：COMMITTED 已提交；预校验失败整批不产生行',
    item_count INT NOT NULL COMMENT '批内核销笔数',
    created_nanos BIGINT NOT NULL COMMENT '提交时间，UTC 纳秒时间戳',
    CONSTRAINT uk_settlement_batch_key UNIQUE (batch_key),
    CONSTRAINT fk_settlement_batch_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='送达核销批次，按完整最终余额与逐笔舍入口径预校验后整体提交';

CREATE TABLE IF NOT EXISTS settlement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '核销凭证主键',
    settlement_key VARCHAR(128) NOT NULL COMMENT '核销业务键，全局唯一（幂等键，重放返回首次凭证）',
    batch_id BIGINT NOT NULL COMMENT '所属核销批次 ID',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    channel_id VARCHAR(128) NOT NULL COMMENT '核销时渠道 ID（取窗口渠道，证据固化）',
    allocation_id BIGINT NOT NULL COMMENT '被扣减上游额度的申请行 ID',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被扣减上游额度的申请业务键',
    rate_id BIGINT NOT NULL COMMENT '固化的蒸发率版本行 ID',
    rate_version INT NOT NULL COMMENT '固化的蒸发率版本号',
    rate_date DATE NOT NULL COMMENT '核销送达日（UTC 自然日）',
    rate_value DECIMAL(6,4) NOT NULL COMMENT '固化的蒸发率，0～0.2000，4 位小数',
    raw_delivered DECIMAL(19,3) NOT NULL COMMENT '原始送达水量，单位立方米，最多 3 位小数，创建后不可改写',
    loss DECIMAL(19,2) NOT NULL COMMENT '蒸发损耗=银行家舍入(送达×率,2)，单位立方米，2 位小数，不可改写',
    upstream_deduction DECIMAL(19,3) NOT NULL COMMENT '上游扣减=送达+损耗，单位立方米，3 位小数，不可改写',
    status VARCHAR(16) NOT NULL COMMENT '状态：SETTLED 已结算 / REVERSED 已被反向更正',
    created_nanos BIGINT NOT NULL COMMENT '核销时间，UTC 纳秒时间戳',
    CONSTRAINT uk_settlement_key UNIQUE (settlement_key),
    CONSTRAINT fk_settlement_batch FOREIGN KEY (batch_id) REFERENCES settlement_batch (id),
    CONSTRAINT fk_settlement_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT fk_settlement_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id),
    CONSTRAINT fk_settlement_rate FOREIGN KEY (rate_id) REFERENCES evaporation_rate (id),
    CONSTRAINT chk_settlement_conservation CHECK (upstream_deduction = raw_delivered + loss)
) COMMENT='送达核销凭证，固化蒸发率版本与逐笔数量；后续改率/转让不改写，只能反向更正';

CREATE TABLE IF NOT EXISTS settlement_correction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '更正记录主键',
    correction_key VARCHAR(128) NOT NULL COMMENT '更正业务键，全局唯一（幂等键）',
    settlement_id BIGINT NOT NULL COMMENT '被更正的原核销凭证 ID（须为 SETTLED）',
    settlement_key VARCHAR(128) NOT NULL COMMENT '被更正的原核销业务键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    delivered_refund DECIMAL(19,3) NOT NULL COMMENT '冲回的送达水量，单位立方米，等于原凭证送达',
    loss_refund DECIMAL(19,2) NOT NULL COMMENT '冲回的蒸发损耗，单位立方米，等于原凭证损耗',
    deduction_refund DECIMAL(19,3) NOT NULL COMMENT '冲回的上游扣减，单位立方米，等于送达冲回+损耗冲回',
    reason VARCHAR(512) NOT NULL COMMENT '更正原因',
    created_nanos BIGINT NOT NULL COMMENT '更正时间，UTC 纳秒时间戳',
    CONSTRAINT uk_correction_key UNIQUE (correction_key),
    CONSTRAINT fk_correction_settlement FOREIGN KEY (settlement_id) REFERENCES settlement (id),
    CONSTRAINT fk_correction_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_correction_conservation CHECK (deduction_refund = delivered_refund + loss_refund)
) COMMENT='核销反向更正凭证；冲回上游额度、送达与损耗累计，原核销证据保留并置 REVERSED';

CREATE TABLE IF NOT EXISTS command_log (
    command_key VARCHAR(128) PRIMARY KEY COMMENT '命令幂等键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/RATE_REGISTER/SETTLEMENT/CORRECTION',
    params VARCHAR(4096) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';
