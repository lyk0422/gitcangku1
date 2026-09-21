-- 校准证书表：证书创建后不可修改，只能撤销；同一仪器未撤销证书区间不得重叠。
CREATE TABLE IF NOT EXISTS calibration_certificate (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '证书主键 ID',
    instrument_id VARCHAR(64)  NOT NULL COMMENT '仪器 ID',
    valid_from    DATETIME(6)  NOT NULL COMMENT 'UTC 有效起点（含，左闭），会话时区 +08:00 存储',
    valid_to      DATETIME(6)  NOT NULL COMMENT 'UTC 有效终点（不含，右开），会话时区 +08:00 存储',
    coefficient_a DECIMAL(38, 12) NOT NULL COMMENT '校准系数 a，输入最多 6 位小数',
    offset_b      DECIMAL(38, 12) NOT NULL COMMENT '校准偏移 b，输入最多 6 位小数',
    revoked       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已撤销：0 否，1 是',
    revoked_at    DATETIME(6)  NULL COMMENT '撤销时刻（UTC），未撤销为 NULL',
    created_at    DATETIME(6)  NOT NULL COMMENT '创建时刻（UTC）',
    KEY idx_cert_instrument (instrument_id, revoked, valid_from, valid_to)
) ENGINE = InnoDB COMMENT ='校准证书：区间左闭右开，创建后不可改只能撤销';

-- 测量记录表：原始读数、计算值与放行信息一经写入不可改写。
CREATE TABLE IF NOT EXISTS measurement (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '测量主键 ID',
    measurement_key VARCHAR(128) NOT NULL COMMENT '业务幂等键，全局唯一',
    instrument_id   VARCHAR(64)  NOT NULL COMMENT '仪器 ID',
    measured_at     DATETIME(6)  NOT NULL COMMENT '测量时刻（UTC）',
    raw_reading     DECIMAL(38, 12) NOT NULL COMMENT '原始读数，输入最多 6 位小数',
    lower_limit     DECIMAL(38, 12) NOT NULL COMMENT '合格下限（含端点），输入最多 6 位小数',
    upper_limit     DECIMAL(38, 12) NOT NULL COMMENT '合格上限（含端点），输入最多 6 位小数',
    submitted_by    VARCHAR(64)  NOT NULL COMMENT '提交人',
    certificate_id  BIGINT       NOT NULL COMMENT '提交时匹配到的证书 ID',
    computed_value  DECIMAL(38, 12) NOT NULL COMMENT '未舍入计算值 a×读数+b，用于合格判断',
    display_value   DECIMAL(38, 4)  NOT NULL COMMENT '显示值，HALF_UP 保留 4 位小数，仅展示用',
    passed          TINYINT(1)   NOT NULL COMMENT '是否合格（基于未舍入值，含端点）：0 否，1 是',
    status          VARCHAR(32)  NOT NULL COMMENT '放行状态：PENDING_RELEASE 待放行，RELEASED 已放行',
    released_by     VARCHAR(64)  NULL COMMENT '放行人，未放行时为 NULL',
    released_at     DATETIME(6)  NULL COMMENT '放行时刻（UTC），未放行时为 NULL',
    created_at      DATETIME(6)  NOT NULL COMMENT '创建时刻（UTC）',
    UNIQUE KEY uk_measurement_key (measurement_key),
    KEY idx_measurement_instrument (instrument_id, status)
) ENGINE = InnoDB COMMENT ='测量记录：提交后不可改写，状态机 PENDING_RELEASE→RELEASED';

-- 放行历史表：证书撤销后历史仍保留，仅影响“当前可用”资格。
CREATE TABLE IF NOT EXISTS release_record (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录主键 ID',
    measurement_id BIGINT      NOT NULL COMMENT '被放行的测量 ID，唯一（一次测量最多放行一次）',
    certificate_id BIGINT      NOT NULL COMMENT '放行时使用的证书 ID',
    released_by    VARCHAR(64) NOT NULL COMMENT '放行人，须不同于提交人',
    released_at    DATETIME(6) NOT NULL COMMENT '放行时刻（UTC）',
    batch_id       VARCHAR(64) NOT NULL COMMENT '所属放行批次 ID',
    UNIQUE KEY uk_release_measurement (measurement_id)
) ENGINE = InnoDB COMMENT ='放行历史：永久保留，不因证书撤销而删除';
