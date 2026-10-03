-- 温室授粉窗口管理建表脚本（H2 MODE=MySQL 与 MySQL 8 兼容）。
-- 所有时间字段均为 UTC 时刻，不带时区；应用层统一以 Instant 读写。

CREATE TABLE IF NOT EXISTS pollen_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_no VARCHAR(64) NOT NULL COMMENT '花粉批号，全局唯一',
    status VARCHAR(16) NOT NULL COMMENT '批号状态：ACTIVE 可用 / DISABLED 已停用',
    created_at TIMESTAMP NOT NULL COMMENT '创建时刻（UTC）',
    CONSTRAINT uk_pollen_batch_no UNIQUE (batch_no)
) COMMENT='花粉批号表';

CREATE TABLE IF NOT EXISTS inflorescence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    code VARCHAR(64) NOT NULL COMMENT '花序编号，全局唯一',
    first_open_at TIMESTAMP NOT NULL COMMENT '首次开放时刻（UTC），授粉窗口起点',
    window_hours INT NOT NULL COMMENT '有效窗口时长，单位小时，默认 24',
    removed_at TIMESTAMP NULL COMMENT '摘除时刻（UTC）；NULL 表示未摘除',
    active_operation_id BIGINT NULL COMMENT '当前有效授粉操作 ID；NULL 表示花序空闲可授粉',
    confirmed_pollen_batch_no VARCHAR(64) NULL COMMENT '确认成功后固化的花粉批号；NULL 表示尚无成功结果',
    confirmed_at TIMESTAMP NULL COMMENT '成功结果确认时刻（UTC）；NULL 表示未确认',
    created_at TIMESTAMP NOT NULL COMMENT '创建时刻（UTC）',
    CONSTRAINT uk_inflorescence_code UNIQUE (code)
) COMMENT='花序表';

CREATE TABLE IF NOT EXISTS pollination_operation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键，客户端生成，全局唯一',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数 SHA-256 摘要，用于同键异参检测',
    inflorescence_id BIGINT NOT NULL COMMENT '花序 ID',
    pollen_batch_no VARCHAR(64) NOT NULL COMMENT '操作时登记的花粉批号',
    technician VARCHAR(64) NOT NULL COMMENT '执行技术员',
    executed_at TIMESTAMP NOT NULL COMMENT '授粉执行时刻（UTC），须落在有效窗口内',
    status VARCHAR(16) NOT NULL COMMENT '操作状态：EFFECTIVE 有效 / CONFIRMED 已确认 / REVOKED 已撤销',
    revoked_at TIMESTAMP NULL COMMENT '撤销时刻（UTC）；NULL 表示未撤销',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时刻（UTC）',
    CONSTRAINT uk_operation_request_id UNIQUE (request_id)
) COMMENT='授粉操作记录表';

CREATE TABLE IF NOT EXISTS pollination_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键，客户端生成，全局唯一',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数 SHA-256 摘要，用于同键异参检测',
    operation_id BIGINT NOT NULL COMMENT '授粉操作 ID，一个操作只允许一条确认',
    result VARCHAR(16) NOT NULL COMMENT '确认结果：SUCCESS 授粉成功 / FAILED 授粉失败',
    technician VARCHAR(64) NOT NULL COMMENT '确认技术员',
    confirmed_at TIMESTAMP NOT NULL COMMENT '结果确认时刻（UTC），须在操作后 72 小时内',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时刻（UTC）',
    CONSTRAINT uk_confirmation_request_id UNIQUE (request_id),
    CONSTRAINT uk_confirmation_operation UNIQUE (operation_id)
) COMMENT='授粉结果确认表';