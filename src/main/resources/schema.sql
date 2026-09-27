-- 灌区配水配额、限供与水库下泄曲线 schema。时间统一为 UTC 纳秒时间戳（BIGINT）。
-- 配额水量单位为立方米，DECIMAL(19,3) 精确到 0.001；下泄相关水量（曲线上限、逐小时预留、核销）DECIMAL(19,2) 精确到 0.01。
CREATE TABLE IF NOT EXISTS supply_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '供水窗口主键',
    window_key VARCHAR(128) NOT NULL COMMENT '窗口业务键，全局唯一',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID，同一渠道窗口不得重叠',
    start_nanos BIGINT NOT NULL COMMENT '窗口开始时刻，UTC 纳秒时间戳',
    end_nanos BIGINT NOT NULL COMMENT '窗口结束时刻（不含），UTC 纳秒时间戳',
    planned_volume DECIMAL(19,3) NOT NULL COMMENT '计划水量，单位立方米，最多 3 位小数',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_supply_window_key UNIQUE (window_key)
) COMMENT='供水窗口，创建后不可修改；配置下泄曲线时起止时刻必须对齐 UTC 整点';

CREATE TABLE IF NOT EXISTS curtailment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '限供记录主键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    volume DECIMAL(19,3) NOT NULL COMMENT '限供水量，单位立方米，大于 0 且不超过计划水量',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 生效中 / CANCELLED 已取消',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    cancelled_nanos BIGINT NULL COMMENT '取消时间，UTC 纳秒时间戳；未取消为 NULL',
    CONSTRAINT fk_curtailment_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='窗口限供，同一窗口至多一条 ACTIVE';

CREATE TABLE IF NOT EXISTS drawdown_curve (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '下泄曲线版本主键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    version INT NOT NULL COMMENT '曲线版本号，同一窗口内从 1 递增；批准记录固化具体版本',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 当前生效版本 / SUPERSEDED 已被新版本替换的历史版本',
    created_nanos BIGINT NOT NULL COMMENT '版本创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_drawdown_curve_version UNIQUE (window_id, version),
    CONSTRAINT fk_drawdown_curve_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='水库下泄曲线版本；每次修改生成新版本，旧版本置 SUPERSEDED 且永不删除';

CREATE TABLE IF NOT EXISTS drawdown_point (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '曲线小时点主键',
    curve_id BIGINT NOT NULL COMMENT '所属曲线版本 ID',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID（冗余，便于窗口级查询）',
    hour_start_nanos BIGINT NOT NULL COMMENT '小时区间起点（含），UTC 整点纳秒时间戳；区间为 [hour_start, hour_start+1h)',
    cap_volume DECIMAL(19,2) NOT NULL COMMENT '该小时下泄上限，单位立方米，非负且最多 2 位小数',
    CONSTRAINT uk_drawdown_point_hour UNIQUE (curve_id, hour_start_nanos),
    CONSTRAINT fk_drawdown_point_curve FOREIGN KEY (curve_id) REFERENCES drawdown_curve (id),
    CONSTRAINT fk_drawdown_point_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_drawdown_cap CHECK (cap_volume >= 0)
) COMMENT='下泄曲线逐小时上限点；每个版本必须覆盖窗口全部小时，不得重复、缺失或跨窗口';

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
    usage_start_nanos BIGINT NULL COMMENT '申请下泄区间起点（含），UTC 纳秒时间戳；窗口配置曲线时必填且对齐整点，未配置曲线为 NULL',
    usage_end_nanos BIGINT NULL COMMENT '申请下泄区间终点（不含），UTC 纳秒时间戳；窗口配置曲线时必填且对齐整点，未配置曲线为 NULL',
    approval_curve_id BIGINT NULL COMMENT '批准（含转让批准）时固化的下泄曲线版本 ID；未批准或无曲线窗口批准为 NULL，后续不回改',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT fk_allocation_curve FOREIGN KEY (approval_curve_id) REFERENCES drawdown_curve (id),
    CONSTRAINT chk_allocation_held CHECK (held_amount >= 0 AND held_amount <= amount)
) COMMENT='配水申请；逐小时预留分布见 allocation_hour_reservation';

-- 既有库升级新列（H2 支持 IF NOT EXISTS；测试与本地入口均为每次新建的 H2 内存库）
ALTER TABLE allocation ADD COLUMN IF NOT EXISTS usage_start_nanos BIGINT NULL COMMENT '申请下泄区间起点（含），UTC 纳秒时间戳；窗口配置曲线时必填且对齐整点，未配置曲线为 NULL';
ALTER TABLE allocation ADD COLUMN IF NOT EXISTS usage_end_nanos BIGINT NULL COMMENT '申请下泄区间终点（不含），UTC 纳秒时间戳；窗口配置曲线时必填且对齐整点，未配置曲线为 NULL';
ALTER TABLE allocation ADD COLUMN IF NOT EXISTS approval_curve_id BIGINT NULL COMMENT '批准（含转让批准）时固化的下泄曲线版本 ID；未批准或无曲线窗口批准为 NULL，后续不回改';

CREATE TABLE IF NOT EXISTS allocation_hour_reservation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '逐小时预留主键',
    allocation_id BIGINT NOT NULL COMMENT '所属配水申请 ID',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID（冗余，便于窗口级聚合）',
    curve_id BIGINT NOT NULL COMMENT '批准时固化的曲线版本 ID；曲线调整不回改本行',
    hour_start_nanos BIGINT NOT NULL COMMENT '预留所属小时起点（含），UTC 整点纳秒时间戳',
    reserved_volume DECIMAL(19,2) NOT NULL COMMENT '批准时固化的该小时预留量，单位立方米，非负最多 2 位小数；创建后不可改写',
    released_volume DECIMAL(19,2) NOT NULL DEFAULT 0 COMMENT '取消时累计释放量，单位立方米；仅取消申请时写入，核销与曲线调整不改变',
    written_off_volume DECIMAL(19,2) NOT NULL DEFAULT 0 COMMENT '该小时累计实际核销量，单位立方米；只能核销同一申请同一小时的预留',
    updated_nanos BIGINT NOT NULL COMMENT '最近释放/核销计数变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_hour UNIQUE (allocation_id, hour_start_nanos),
    CONSTRAINT fk_reservation_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id),
    CONSTRAINT fk_reservation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT fk_reservation_curve FOREIGN KEY (curve_id) REFERENCES drawdown_curve (id),
    CONSTRAINT chk_reservation_totals CHECK (reserved_volume >= 0
        AND released_volume >= 0 AND written_off_volume >= 0
        AND released_volume + written_off_volume <= reserved_volume)
) COMMENT='批准时固化的逐小时预留快照及释放/核销计数；reserved 与 curve_id 不可改写';

CREATE TABLE IF NOT EXISTS writeoff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '核销流水主键',
    writeoff_key VARCHAR(128) NOT NULL COMMENT '核销业务键，全局唯一；换 commandKey 复用返回 409',
    allocation_id BIGINT NOT NULL COMMENT '被核销配水申请 ID',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被核销配水申请业务键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    hour_start_nanos BIGINT NOT NULL COMMENT '实际下泄发生小时起点（含），UTC 整点纳秒时间戳；只扣减同一小时额度',
    volume DECIMAL(19,2) NOT NULL COMMENT '本次核销量，单位立方米，大于 0 且最多 2 位小数',
    actor VARCHAR(128) NOT NULL COMMENT '核销操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '核销时间，UTC 纳秒时间戳',
    CONSTRAINT uk_writeoff_key UNIQUE (writeoff_key),
    CONSTRAINT fk_writeoff_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id),
    CONSTRAINT fk_writeoff_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_writeoff_volume CHECK (volume > 0)
) COMMENT='实际下泄核销流水，创建后不可变；按发生小时扣减同一申请同一小时预留，不能挪用其他小时余量';

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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_APPROVE_BATCH/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/TRANSFER_BATCH/DRAWDOWN_PUT/WRITEOFF',
    params MEDIUMTEXT NOT NULL COMMENT '规范化请求参数串，用于同键改参检测；批量/曲线参数可能较长',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409，失败不占键';
