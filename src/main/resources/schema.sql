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
    held_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '当前持有额度（申请剩余额度），单位立方米；批准时等于原申请水量，取消时归零，转出与掺配核销时等额扣减',
    max_salinity_mg_l DECIMAL(19,3) NULL COMMENT '申报的掺配盐度上限，单位毫克每升，最多 3 位小数；NULL 表示未声明，核销时不做盐度校验',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '申请乐观版本号，批准/取消/转让/核销每次变更加 1；掺配核销 blendKey 指纹包含该版本',
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

CREATE TABLE IF NOT EXISTS water_source (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '水源主键',
    source_id VARCHAR(128) NOT NULL COMMENT '水源业务键（sourceId），全局唯一',
    available_amount DECIMAL(19,3) NOT NULL COMMENT '当前可用水量，单位立方米，最多 3 位小数；掺配核销时扣减，扣减后不因窗口取消等原因恢复',
    salinity_mg_l DECIMAL(19,3) NOT NULL COMMENT '当前盐度，单位毫克每升，最多 3 位小数；修改只影响后续核销，不改写历史快照',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '水源乐观版本号，盐度每次修改加 1；修改须携带 expectedVersion',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_water_source_id UNIQUE (source_id),
    CONSTRAINT chk_water_source_available CHECK (available_amount >= 0)
) COMMENT='掺配水源，可用量与盐度随核销/修改变化';

CREATE TABLE IF NOT EXISTS blend_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '掺配快照主键',
    blend_key VARCHAR(128) NOT NULL COMMENT '掺配业务键（blendKey），全局唯一；指纹含操作者、申请版本、规范化水源集合与数量',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被核销的配水申请业务键',
    allocation_version BIGINT NOT NULL COMMENT '核销时刻的申请版本号（请求携带并校验）',
    operator VARCHAR(128) NOT NULL COMMENT '操作人（X-Actor-Id）',
    settle_amount DECIMAL(19,3) NOT NULL COMMENT '本次核销量，单位立方米，等于各水源取水量之和',
    salt_total DECIMAL(38,9) NOT NULL COMMENT '本次核销盐量总和（各水源取水量 x 盐度之和），单位毫克每升 x 立方米，精确值用于累计盐度计算',
    weighted_salinity_mg_l DECIMAL(19,6) NOT NULL COMMENT '本次核销加权平均盐度，单位毫克每升，6 位小数（四舍五入），用于展示',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_blend_snapshot_key UNIQUE (blend_key)
) COMMENT='掺配核销快照，与水源扣减、申请额度扣减同事务提交，创建后不可变，不提供撤销';

CREATE TABLE IF NOT EXISTS blend_snapshot_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '掺配快照明细主键',
    snapshot_id BIGINT NOT NULL COMMENT '所属掺配快照 ID',
    source_id VARCHAR(128) NOT NULL COMMENT '取水水源业务键',
    source_version BIGINT NOT NULL COMMENT '核销时刻的水源版本号（冻结）',
    amount DECIMAL(19,3) NOT NULL COMMENT '该水源取水量，单位立方米，最多 3 位小数',
    salinity_mg_l DECIMAL(19,3) NOT NULL COMMENT '核销时刻该水源盐度（冻结），单位毫克每升',
    CONSTRAINT fk_blend_line_snapshot FOREIGN KEY (snapshot_id) REFERENCES blend_snapshot (id)
) COMMENT='掺配快照水源明细，冻结核销时刻的取水量、盐度与水源版本，创建后不可变';

CREATE TABLE IF NOT EXISTS command_log (
    command_key VARCHAR(128) PRIMARY KEY COMMENT '命令幂等键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/SOURCE_CREATE/SOURCE_SALINITY_UPDATE/BLEND',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409，失败不占键';
