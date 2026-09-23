-- 灌区配水配额与限供 schema。时间统一为 UTC 纳秒时间戳（BIGINT），水量单位为立方米，DECIMAL(19,3) 精确到 0.001。
CREATE TABLE IF NOT EXISTS supply_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '供水窗口主键',
    window_key VARCHAR(128) NOT NULL COMMENT '窗口业务键，全局唯一',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID，同一渠道窗口不得重叠',
    start_nanos BIGINT NOT NULL COMMENT '窗口开始时刻，UTC 纳秒时间戳',
    end_nanos BIGINT NOT NULL COMMENT '窗口结束时刻（不含），UTC 纳秒时间戳',
    planned_volume DECIMAL(19,3) NOT NULL COMMENT '计划水量，单位立方米，最多 3 位小数',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT '窗口状态：OPEN 开放中 / CLOSED 已关闭；关闭后重平衡整单拒绝',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_supply_window_key UNIQUE (window_key)
) COMMENT='供水窗口，创建后仅可关闭，不可修改其他属性';

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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

-- ==================== 多水源配额矩阵 ====================

CREATE TABLE IF NOT EXISTS water_source (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '水源主键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    source_id VARCHAR(64) NOT NULL COMMENT '窗口内水源业务 ID（1~10 个，窗口内唯一）',
    supply_cap DECIMAL(19,3) NOT NULL COMMENT '供给上限，单位立方米，非负，最多 3 位小数；矩阵中该水源总分配不得超过',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_water_source UNIQUE (window_id, source_id),
    CONSTRAINT fk_water_source_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_water_source_cap CHECK (supply_cap >= 0)
) COMMENT='配水窗口的水源及供给上限，每窗口 1~10 个';

CREATE TABLE IF NOT EXISTS block_quota (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '区块额度单元主键（一个区块一个水源一行，矩阵单元格）',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    block_id VARCHAR(64) NOT NULL COMMENT '区块业务 ID（既有用水区块）',
    source_id VARCHAR(64) NOT NULL COMMENT '该额度绑定的水源；同一区块只允许适用水源各一行',
    quota DECIMAL(19,3) NOT NULL COMMENT '该区块在该水源下的分配额度，单位立方米，非负，重平衡时守恒调整',
    consumed DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '已核销用水量，单位立方米，非负且不超过 quota；重平衡不能搬走该部分',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次重平衡更新逐记录 +1；expectedVersion 不匹配整单 409',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近额度变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_block_quota UNIQUE (window_id, block_id, source_id),
    CONSTRAINT fk_block_quota_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_block_quota_amount CHECK (quota >= 0 AND consumed >= 0 AND consumed <= quota)
) COMMENT='区块-水源配额矩阵单元格，一个区块额度绑定一个水源，重平衡在单元格间搬水';

CREATE TABLE IF NOT EXISTS writeoff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用水核销流水主键',
    writeoff_key VARCHAR(128) NOT NULL COMMENT '核销业务键，全局唯一（携带 commandKey 幂等）',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    block_id VARCHAR(64) NOT NULL COMMENT '区块业务 ID',
    source_id VARCHAR(64) NOT NULL COMMENT '水源业务 ID（核销的单元格水源）',
    volume DECIMAL(19,3) NOT NULL COMMENT '本次核销量，单位立方米，为正、最多 3 位小数',
    actor VARCHAR(128) NOT NULL COMMENT '操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_writeoff_key UNIQUE (writeoff_key),
    CONSTRAINT fk_writeoff_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='实际用水核销流水，累加单元格 consumed，已核销部分重平衡不能搬走';

CREATE TABLE IF NOT EXISTS block_source_applicability (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '适用性主键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    block_id VARCHAR(64) NOT NULL COMMENT '区块业务 ID',
    source_id VARCHAR(64) NOT NULL COMMENT '适用水源；目标水源必须在该区块适用集合内',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_block_source UNIQUE (window_id, block_id, source_id),
    CONSTRAINT fk_block_source_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='区块可适用水源白名单；目标水源适用性变化会使重平衡整单 409';

CREATE TABLE IF NOT EXISTS rebalance_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '重平衡单主键',
    rebalance_key VARCHAR(128) NOT NULL COMMENT '重平衡单业务键，全局唯一；换 requestId 复用返回 409',
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求键；同参重放首次快照，规范化等价即同参，异参 409，失败不占键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 已激活（一次性，不可撤销）',
    normalized_params MEDIUMTEXT NOT NULL COMMENT '规范化请求参数串（明细按区块/源/目标排序合并），用于同参判定',
    detail_count INT NOT NULL COMMENT '规范化后的明细条数',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_rebalance_key UNIQUE (rebalance_key),
    CONSTRAINT uk_rebalance_request UNIQUE (request_id),
    CONSTRAINT fk_rebalance_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='多水源重平衡单，激活时冻结明细、前后矩阵、上限与核销量快照';

CREATE TABLE IF NOT EXISTS rebalance_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '重平衡明细主键',
    order_id BIGINT NOT NULL COMMENT '所属重平衡单 ID',
    block_id VARCHAR(64) NOT NULL COMMENT '区块业务 ID',
    source_source_id VARCHAR(64) NOT NULL COMMENT '源水源（扣减方）',
    target_source_id VARCHAR(64) NOT NULL COMMENT '目标水源（增加方）',
    volume DECIMAL(19,3) NOT NULL COMMENT '调整立方米，为正、最多 3 位小数；同区块同源目标重复明细已规范化求和',
    seq_no INT NOT NULL COMMENT '规范化后的稳定序号（按区块、源水源、目标水源排序）',
    CONSTRAINT fk_rebalance_detail_order FOREIGN KEY (order_id) REFERENCES rebalance_order (id)
) COMMENT='重平衡规范化明细快照，创建后不可变';

CREATE TABLE IF NOT EXISTS rebalance_matrix_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '矩阵快照主键',
    order_id BIGINT NOT NULL COMMENT '所属重平衡单 ID',
    phase VARCHAR(8) NOT NULL COMMENT '快照阶段：BEFORE 前态 / AFTER 后态',
    block_id VARCHAR(64) NOT NULL COMMENT '区块业务 ID',
    source_id VARCHAR(64) NOT NULL COMMENT '水源业务 ID',
    quota DECIMAL(19,3) NOT NULL COMMENT '该单元格额度，单位立方米',
    consumed DECIMAL(19,3) NOT NULL COMMENT '该单元格已核销量，单位立方米',
    version BIGINT NOT NULL COMMENT '该单元格在本单激活时的版本（后态为增版后的新版本）',
    supply_cap DECIMAL(19,3) NOT NULL COMMENT '该水源供给上限快照，单位立方米',
    seq_no INT NOT NULL COMMENT '稳定排序序号（按水源、区块）',
    CONSTRAINT fk_rebalance_snapshot_order FOREIGN KEY (order_id) REFERENCES rebalance_order (id)
) COMMENT='重平衡前后矩阵 + 上限 + 核销量快照，作为只读证据按水源、区块稳定排序';
