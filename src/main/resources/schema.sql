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
    held_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '当前持有额度，单位立方米；批准时等于原申请水量，取消时保留已执行量（未执行部分释放），转出时等额扣减',
    executed_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '累计已执行水量，单位立方米；闸门执行成功不可取消返还，恒有 0 <= executed_amount <= held_amount',
    requester VARCHAR(128) NOT NULL COMMENT '申请人（X-Actor-Id）',
    status VARCHAR(16) NOT NULL COMMENT '状态：REQUESTED 已申请 / APPROVED 已批准 / CANCELLED 已取消（不可恢复）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_allocation_held CHECK (held_amount >= 0 AND held_amount <= amount),
    CONSTRAINT chk_allocation_executed CHECK (executed_amount >= 0 AND executed_amount <= held_amount)
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

CREATE TABLE IF NOT EXISTS gate_basin (
    window_id BIGINT PRIMARY KEY COMMENT '所属供水窗口 ID，与 supply_window 一对一；无此行表示普通窗口，不启用双池规则',
    upper_area_m2 BIGINT NOT NULL COMMENT '上池横截面积，单位平方米，固定正整数',
    upper_volume_m3 BIGINT NOT NULL COMMENT '上池当前体积，单位立方米，整数',
    upper_min_m3 BIGINT NOT NULL COMMENT '上池体积下界（含），单位立方米，非负整数',
    upper_max_m3 BIGINT NOT NULL COMMENT '上池体积上界（含），单位立方米',
    lower_area_m2 BIGINT NOT NULL COMMENT '下池横截面积，单位平方米，固定正整数',
    lower_volume_m3 BIGINT NOT NULL COMMENT '下池当前体积，单位立方米，整数',
    lower_min_m3 BIGINT NOT NULL COMMENT '下池体积下界（含），单位立方米，非负整数',
    lower_max_m3 BIGINT NOT NULL COMMENT '下池体积上界（含），单位立方米',
    version BIGINT NOT NULL COMMENT '双池状态版本号，每次成功执行加 1；执行命令须携带期望版本',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近变更时间，UTC 纳秒时间戳',
    CONSTRAINT fk_gate_basin_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_gate_basin_upper CHECK (upper_area_m2 > 0 AND upper_min_m3 >= 0
        AND upper_max_m3 >= upper_min_m3 AND upper_volume_m3 BETWEEN upper_min_m3 AND upper_max_m3),
    CONSTRAINT chk_gate_basin_lower CHECK (lower_area_m2 > 0 AND lower_min_m3 >= 0
        AND lower_max_m3 >= lower_min_m3 AND lower_volume_m3 BETWEEN lower_min_m3 AND lower_max_m3)
) COMMENT='闸门窗口双池状态；水位=体积/面积，单位米，执行前后两池水位差绝对值均不得超过 2 米';

CREATE TABLE IF NOT EXISTS gate_execution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '执行明细主键',
    execution_key VARCHAR(128) NOT NULL COMMENT '执行明细业务键，全局唯一；换 commandKey 复用返回 409',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被执行的配水申请业务键（执行时须为 APPROVED）',
    volume_m3 BIGINT NOT NULL COMMENT '本次执行水量，单位立方米，正整数；从上池扣除并加入下池',
    upper_volume_before_m3 BIGINT NOT NULL COMMENT '执行前上池体积，单位立方米',
    upper_volume_after_m3 BIGINT NOT NULL COMMENT '执行后上池体积，单位立方米',
    lower_volume_before_m3 BIGINT NOT NULL COMMENT '执行前下池体积，单位立方米',
    lower_volume_after_m3 BIGINT NOT NULL COMMENT '执行后下池体积，单位立方米',
    upper_level_before_m DECIMAL(30,12) NOT NULL COMMENT '执行前上池水位，单位米；显示舍入值，水位门禁使用整数精确比对',
    upper_level_after_m DECIMAL(30,12) NOT NULL COMMENT '执行后上池水位，单位米；显示舍入值',
    lower_level_before_m DECIMAL(30,12) NOT NULL COMMENT '执行前下池水位，单位米；显示舍入值',
    lower_level_after_m DECIMAL(30,12) NOT NULL COMMENT '执行后下池水位，单位米；显示舍入值',
    basin_version BIGINT NOT NULL COMMENT '本次执行后的双池状态版本号',
    actor VARCHAR(128) NOT NULL COMMENT '操作人（X-Actor-Id），非空',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_gate_execution_key UNIQUE (execution_key),
    CONSTRAINT fk_gate_execution_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='闸门执行明细，与双池体积变更、申请已执行量同事务提交，创建后不可变，成功水量不可取消返还';

CREATE TABLE IF NOT EXISTS command_log (
    command_key VARCHAR(128) PRIMARY KEY COMMENT '命令幂等键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/GATE_WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/GATE_EXECUTE',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串（含操作者、资源、全参数及期望版本），用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';
