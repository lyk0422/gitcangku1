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
    held_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '当前持有额度，单位立方米；批准时等于原申请水量，取消时归零，转让与掺配核销时等额扣减',
    salinity_limit DECIMAL(12,3) NULL COMMENT '申请声明的盐度上限，单位毫克每升（mg/L）；NULL 表示不限制，掺配核销加权平均盐度不得高于该值',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '申请乐观版本，批准/取消/转让/掺配核销每次扣减自增 1，掺配指纹含核销时版本',
    requester VARCHAR(128) NOT NULL COMMENT '申请人（X-Actor-Id）',
    status VARCHAR(16) NOT NULL COMMENT '状态：REQUESTED 已申请 / APPROVED 已批准 / CANCELLED 已取消（不可恢复）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_allocation_held CHECK (held_amount >= 0 AND held_amount <= amount),
    CONSTRAINT chk_allocation_salinity CHECK (salinity_limit IS NULL OR salinity_limit >= 0),
    CONSTRAINT chk_allocation_version CHECK (version >= 0)
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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/SOURCE_CREATE/SOURCE_UPDATE_SALINITY/BLEND_WRITE_OFF',
    params VARCHAR(4096) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

CREATE TABLE IF NOT EXISTS water_source (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '水源主键',
    source_key VARCHAR(128) NOT NULL COMMENT '水源业务键，全局唯一',
    available_amount DECIMAL(19,3) NOT NULL COMMENT '可用水量，单位立方米，最多 3 位小数；掺配核销成功时在同事务扣减',
    salinity DECIMAL(12,3) NOT NULL COMMENT '水源盐度，单位毫克每升（mg/L），非负；修改携带 expectedVersion 做乐观并发裁决',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '水源乐观版本，创建为 0，盐度每次修改自增 1；仅影响后续核销，不改写历史快照',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近盐度修改时间，UTC 纳秒时间戳；未修改等于创建时间',
    CONSTRAINT uk_water_source_key UNIQUE (source_key),
    CONSTRAINT chk_water_source_amount CHECK (available_amount >= 0),
    CONSTRAINT chk_water_source_salinity CHECK (salinity >= 0),
    CONSTRAINT chk_water_source_version CHECK (version >= 0)
) COMMENT='水源：含可用量与盐度（mg/L），取消配水窗口不恢复已扣水源量';

CREATE TABLE IF NOT EXISTS blend_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '掺配核销快照主键',
    blend_key VARCHAR(128) NOT NULL COMMENT '掺配业务键（幂等指纹），全局唯一；同键同参重放首次完整结果，失败不占键',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被核销的配水申请业务键（核销时须 APPROVED 且持有额度充足）',
    actor VARCHAR(128) NOT NULL COMMENT '核销操作人（X-Actor-Id），指纹组成部分',
    total_amount DECIMAL(19,3) NOT NULL COMMENT '本次核销总量，单位立方米，等于申请本次核销量且等于各水源取水量之和',
    weighted_salinity DECIMAL(15,6) NOT NULL COMMENT '按取水量加权平均盐度，单位毫克每升（mg/L），三位小数入参加权、保留 6 位计算结果',
    salinity_limit DECIMAL(12,3) NULL COMMENT '核销时申请声明的盐度上限快照，单位毫克每升（mg/L）；NULL 表示申请未限制盐度',
    allocation_version BIGINT NOT NULL COMMENT '核销时申请乐观版本快照，blendKey 指纹组成部分',
    created_nanos BIGINT NOT NULL COMMENT '核销时间，UTC 纳秒时间戳；快照创建后不可变、不提供撤销',
    CONSTRAINT uk_blend_snapshot_key UNIQUE (blend_key)
) COMMENT='水质掺配核销不可变快照：水源盐度后续修改不改写本行';

CREATE TABLE IF NOT EXISTS blend_snapshot_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '快照明细主键',
    snapshot_id BIGINT NOT NULL COMMENT '所属掺配快照 ID',
    source_key VARCHAR(128) NOT NULL COMMENT '水源业务键；一次核销 1 至 5 个，同一核销内不得重复',
    amount DECIMAL(19,3) NOT NULL COMMENT '从该水源的取水量，单位立方米，三位小数，必须为正',
    salinity DECIMAL(12,3) NOT NULL COMMENT '核销时刻水源盐度快照，单位毫克每升（mg/L）；水源盐度事后修改不影响本值',
    ordinal INT NOT NULL COMMENT '规范化水源集合排序序号（按 source_key 升序，从 0 开始），换序同参',
    CONSTRAINT fk_blend_item_snapshot FOREIGN KEY (snapshot_id) REFERENCES blend_snapshot (id),
    CONSTRAINT uk_blend_item_snapshot_source UNIQUE (snapshot_id, source_key),
    CONSTRAINT chk_blend_item_amount CHECK (amount > 0),
    CONSTRAINT chk_blend_item_ordinal CHECK (ordinal >= 0 AND ordinal <= 4)
) COMMENT='掺配快照明细：冻结各水源取水量与核销时盐度，随快照同事务写入';
