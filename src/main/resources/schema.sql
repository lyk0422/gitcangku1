-- 灌区配水配额与旱情削减 schema。时间统一为 UTC 纳秒时间戳（BIGINT），水量单位为立方米，DECIMAL(19,3) 精确到 0.001。
CREATE TABLE IF NOT EXISTS supply_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '供水窗口主键',
    window_key VARCHAR(128) NOT NULL COMMENT '窗口业务键，全局唯一',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID，同一渠道窗口不得重叠',
    start_nanos BIGINT NOT NULL COMMENT '窗口开始时刻，UTC 纳秒时间戳',
    end_nanos BIGINT NOT NULL COMMENT '窗口结束时刻（不含），UTC 纳秒时间戳',
    planned_volume DECIMAL(19,3) NOT NULL COMMENT '计划水量，单位立方米，最多 3 位小数',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '旱情声明版本号，每有一次旱情等级声明显式生效则加 1，用于 expectedVersion 乐观并发裁决；初始 0 表示从未声明',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_supply_window_key UNIQUE (window_key)
) COMMENT='供水窗口，创建后不可修改（version 除外）';

CREATE TABLE IF NOT EXISTS curtailment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '限供记录主键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    volume DECIMAL(19,3) NOT NULL COMMENT '限供水量，单位立方米，大于 0 且不超过计划水量',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 生效中 / CANCELLED 已取消',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    cancelled_nanos BIGINT NULL COMMENT '取消时间，UTC 纳秒时间戳；未取消为 NULL',
    CONSTRAINT fk_curtailment_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='窗口限供（按总量），同一窗口至多一条 ACTIVE；与旱情比例削减相互独立但共用窗口行锁裁决';

CREATE TABLE IF NOT EXISTS allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '配水申请主键',
    allocation_key VARCHAR(128) NOT NULL COMMENT '申请业务键，全局唯一',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    user_id VARCHAR(128) NOT NULL COMMENT '用水户 ID',
    priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '旱情削减优先级：ESSENTIAL 必保 / NORMAL 一般 / DEFERRABLE 可延后；百分比约束 ESSENTIAL<=NORMAL<=DEFERRABLE',
    amount DECIMAL(19,3) NOT NULL COMMENT '原申请水量，单位立方米，最多 3 位小数，创建后不可改写',
    held_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '当前持有额度，单位立方米；批准时等于原申请水量，旱情削减时按优先级比例下调，取消时归零，转出时等额扣减',
    baseline_held_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '旱情削减基准持有额度，单位立方米；批准时记为完整额度，旱情削减不改写，转让时随水权等额从源扣减、转入时记为完整额度，使基准总量守恒',
    requester VARCHAR(128) NOT NULL COMMENT '申请人（X-Actor-Id）',
    status VARCHAR(16) NOT NULL COMMENT '状态：REQUESTED 已申请 / APPROVED 已批准 / CANCELLED 已取消（不可恢复）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_allocation_held CHECK (held_amount >= 0 AND held_amount <= amount),
    CONSTRAINT chk_allocation_baseline CHECK (baseline_held_amount >= 0 AND baseline_held_amount <= amount)
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
) COMMENT='配水额度转让流水，与源扣减、目标批准同事务提交，创建后不可变，不提供撤销；旱情期间不回溯改写';

CREATE TABLE IF NOT EXISTS drought_curtailment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '旱情削减声明主键',
    curtailment_key VARCHAR(128) NOT NULL COMMENT '旱情削减声明业务键，全局唯一；失败回滚不占键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    level VARCHAR(16) NOT NULL COMMENT '旱情等级：NONE 无旱情 / LEVEL1 / LEVEL2 / LEVEL3；NONE 时三级百分比必须全为 0',
    essential_pct INT NOT NULL COMMENT 'ESSENTIAL 必保级削减百分比，整数 0~100，单位 %',
    normal_pct INT NOT NULL COMMENT 'NORMAL 一般级削减百分比，整数 0~100，单位 %；须 >= essential_pct',
    deferrable_pct INT NOT NULL COMMENT 'DEFERRABLE 可延后级削减百分比，整数 0~100，单位 %；须 >= normal_pct',
    expected_version BIGINT NOT NULL COMMENT '提交时携带的窗口 expectedVersion，与窗口当前 version 不一致则 409',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 当前生效声明（含 NONE）/ SUPERSEDED 已被后续声明覆盖',
    created_nanos BIGINT NOT NULL COMMENT '创建（生效）时间，UTC 纳秒时间戳',
    superseded_nanos BIGINT NULL COMMENT '被覆盖时间，UTC 纳秒时间戳；当前生效为 NULL',
    CONSTRAINT uk_drought_curtailment_key UNIQUE (curtailment_key),
    CONSTRAINT fk_drought_curtailment_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_drought_pct_range CHECK (essential_pct BETWEEN 0 AND 100 AND normal_pct BETWEEN 0 AND 100
        AND deferrable_pct BETWEEN 0 AND 100),
    CONSTRAINT chk_drought_pct_order CHECK (essential_pct <= normal_pct AND normal_pct <= deferrable_pct)
) COMMENT='旱情分级比例削减声明，同一窗口至多一条 ACTIVE，后一声明在窗口行锁内覆盖前者并按基准持有额度整体重算';

CREATE TABLE IF NOT EXISTS drought_curtailment_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '旱情削减明细主键',
    curtailment_id BIGINT NOT NULL COMMENT '所属旱情削减声明 ID',
    allocation_id BIGINT NOT NULL COMMENT '被削减申请 ID',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被削减申请业务键，同级取整差额按该标识升序由首笔承担',
    priority VARCHAR(16) NOT NULL COMMENT '申请在该次声明时的优先级：ESSENTIAL/NORMAL/DEFERRABLE',
    original_held DECIMAL(19,3) NOT NULL COMMENT '原始持有额度（基准），单位立方米，即 allocation.baseline_held_amount',
    target_held DECIMAL(19,3) NOT NULL COMMENT '削减后目标持有额度，单位立方米，3 位小数 HALF_UP 且同级总量守恒',
    reduced_amount DECIMAL(19,3) NOT NULL COMMENT '本笔削减量，单位立方米，= original_held - target_held',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT fk_drought_detail_curtailment FOREIGN KEY (curtailment_id) REFERENCES drought_curtailment (id),
    CONSTRAINT fk_drought_detail_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id),
    CONSTRAINT chk_drought_detail_target CHECK (target_held >= 0 AND target_held <= original_held),
    CONSTRAINT chk_drought_detail_reduced CHECK (reduced_amount >= 0 AND reduced_amount = original_held - target_held)
) COMMENT='旱情削减逐笔明细，随声明同事务写入，创建后不可变';

CREATE TABLE IF NOT EXISTS command_log (
    command_key VARCHAR(128) PRIMARY KEY COMMENT '命令幂等键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/DROUGHT_DECLARE/TRANSFER',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409，失败回滚不占键';
