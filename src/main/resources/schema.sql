-- 灌区配水配额与限供 schema。时间统一为 UTC 纳秒时间戳（BIGINT），水量单位为立方米，DECIMAL(19,3) 精确到 0.001。
CREATE TABLE IF NOT EXISTS supply_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '供水窗口主键',
    window_key VARCHAR(128) NOT NULL COMMENT '窗口业务键，全局唯一',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID，同一渠道窗口不得重叠',
    start_nanos BIGINT NOT NULL COMMENT '窗口开始时刻，UTC 纳秒时间戳',
    end_nanos BIGINT NOT NULL COMMENT '窗口结束时刻（不含），UTC 纳秒时间戳',
    planned_volume DECIMAL(19,3) NOT NULL COMMENT '计划水量，单位立方米，最多 3 位小数',
    drought_level VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT '当前旱情等级：NONE 无 / LEVEL1 / LEVEL2 / LEVEL3',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '窗口版本号，自 0 起，旱情等级每次变更递增 1，用于 expectedVersion 乐观校验',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_supply_window_key UNIQUE (window_key)
) COMMENT='供水窗口，创建后不可修改（旱情等级与版本号除外）';

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
    priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '申请优先级：ESSENTIAL 必需 / NORMAL 一般 / DEFERRABLE 可推迟；缺省 NORMAL',
    held_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '当前持有额度，单位立方米；批准时等于原申请水量，取消时归零，转出时等额扣减，旱情削减时按等级比例调减',
    base_held_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '旱情削减前持有额度（基线），单位立方米；批准时等于原申请水量，取消时归零，转出时等额扣减；旱情削减只改 held_amount 不改本基线',
    requester VARCHAR(128) NOT NULL COMMENT '申请人（X-Actor-Id）',
    status VARCHAR(16) NOT NULL COMMENT '状态：REQUESTED 已申请 / APPROVED 已批准 / CANCELLED 已取消（不可恢复）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_allocation_held CHECK (held_amount >= 0 AND held_amount <= amount),
    CONSTRAINT chk_allocation_base_held CHECK (base_held_amount >= 0 AND base_held_amount <= amount)
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

CREATE TABLE IF NOT EXISTS drought_curtailment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '旱情削减记录主键',
    curtailment_key VARCHAR(128) NOT NULL COMMENT '旱情削减业务键，全局唯一；换 commandKey 复用返回 409',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    level VARCHAR(16) NOT NULL COMMENT '声明旱情等级：NONE 恢复 / LEVEL1 / LEVEL2 / LEVEL3',
    essential_pct INT NOT NULL COMMENT 'ESSENTIAL 优先级削减百分比，0~100 整数',
    normal_pct INT NOT NULL COMMENT 'NORMAL 优先级削减百分比，0~100 整数',
    deferrable_pct INT NOT NULL COMMENT 'DEFERRABLE 优先级削减百分比，0~100 整数',
    window_version BIGINT NOT NULL COMMENT '本次生效后的窗口版本号',
    created_nanos BIGINT NOT NULL COMMENT '声明时间，UTC 纳秒时间戳',
    CONSTRAINT uk_drought_curtailment_key UNIQUE (curtailment_key),
    CONSTRAINT fk_drought_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='旱情分级削减声明，同一窗口最新一条为当前生效等级，历史记录不可变';

CREATE TABLE IF NOT EXISTS drought_curtailment_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '削减明细主键',
    curtailment_id BIGINT NOT NULL COMMENT '所属旱情削减记录 ID',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被调整申请业务键',
    priority VARCHAR(16) NOT NULL COMMENT '申请优先级：ESSENTIAL / NORMAL / DEFERRABLE',
    previous_held DECIMAL(19,3) NOT NULL COMMENT '调整前持有额度，单位立方米',
    new_held DECIMAL(19,3) NOT NULL COMMENT '调整后持有额度，单位立方米',
    CONSTRAINT fk_drought_detail_curtailment FOREIGN KEY (curtailment_id) REFERENCES drought_curtailment (id)
) COMMENT='旱情削减逐申请明细，创建后不可变';

CREATE TABLE IF NOT EXISTS command_log (
    command_key VARCHAR(128) PRIMARY KEY COMMENT '命令幂等键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/DROUGHT_DECLARE',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';
