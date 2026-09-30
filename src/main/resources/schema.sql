-- 削峰邀约结算业务链建表脚本；H2 内存库 MODE=MySQL 下执行，语法同时兼容 MySQL。
-- 时间列一律存 UTC 瞬时，毫秒精度；时段语义为左闭右开。

CREATE TABLE IF NOT EXISTS demand_offer (
    id          VARCHAR(64)  PRIMARY KEY COMMENT '邀约ID（调度方事件标识）',
    start_time  DATETIME(3)  NOT NULL COMMENT '时段起点UTC，左闭',
    end_time    DATETIME(3)  NOT NULL COMMENT '时段终点UTC，右开',
    created_at  DATETIME(3)  NOT NULL COMMENT '邀约创建时间UTC'
);

CREATE TABLE IF NOT EXISTS commitment (
    id             BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '承诺自增主键',
    offer_id       VARCHAR(64)  NOT NULL COMMENT '邀约ID',
    user_id        VARCHAR(64)  NOT NULL COMMENT '用户ID',
    committed_kwh  DECIMAL(10,1) NOT NULL COMMENT '承诺减少用电量，单位kWh，范围(0,50]',
    request_id     VARCHAR(128) NOT NULL COMMENT '承诺请求幂等键',
    created_at     DATETIME(3)  NOT NULL COMMENT '承诺创建时间UTC',
    -- 每用户每邀约至多一个有效承诺；request_id 全局唯一保证重放幂等
    CONSTRAINT uk_commitment_offer_user UNIQUE (offer_id, user_id),
    CONSTRAINT uk_commitment_request   UNIQUE (request_id)
);

CREATE TABLE IF NOT EXISTS meter_reading (
    id                BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT '读数自增主键，行不可变',
    offer_id          VARCHAR(64)   NOT NULL COMMENT '邀约ID',
    user_id           VARCHAR(64)   NOT NULL COMMENT '用户ID',
    kind              VARCHAR(16)   NOT NULL COMMENT '读数种类：BASELINE基线/ACTUAL实际',
    version           INT           NOT NULL COMMENT '读数版本，从1起递增；更正只插新版本',
    value_kwh         DECIMAL(12,3) NOT NULL COMMENT '电量读数，单位kWh，不允许为负',
    period_start      DATETIME(3)   NOT NULL COMMENT '计量时段起点UTC，左闭',
    period_end        DATETIME(3)   NOT NULL COMMENT '计量时段终点UTC，右开',
    correction_reason VARCHAR(255)  NULL COMMENT '更正缘由；首次读数为空，校正记录必填',
    created_at        DATETIME(3)   NOT NULL COMMENT '读数记录创建时间UTC',
    -- 同一邀约、用户、读数种类的版本号唯一
    CONSTRAINT uk_reading_version UNIQUE (offer_id, user_id, kind, version)
);

CREATE TABLE IF NOT EXISTS settlement (
    id                       BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT '账目自增主键，入账不可变',
    offer_id                 VARCHAR(64)   NOT NULL COMMENT '邀约ID',
    user_id                  VARCHAR(64)   NOT NULL COMMENT '用户ID',
    type                     VARCHAR(16)   NOT NULL COMMENT '账目类型：INITIAL首次结算/ADJUSTMENT更正差额',
    baseline_reading_id      BIGINT        NULL COMMENT '固化的基线读数行ID',
    actual_reading_id        BIGINT        NULL COMMENT '固化的实际读数行ID',
    baseline_version         INT           NOT NULL COMMENT '固化的基线读数版本',
    actual_version           INT           NOT NULL COMMENT '固化的实际读数版本',
    committed_kwh            DECIMAL(10,1) NOT NULL COMMENT '承诺减少用电量，单位kWh',
    baseline_kwh             DECIMAL(12,3) NOT NULL COMMENT '基线电量，单位kWh',
    actual_kwh               DECIMAL(12,3) NOT NULL COMMENT '实际电量，单位kWh',
    reduction_kwh            DECIMAL(12,3) NOT NULL COMMENT '实际压降=基线-实际，不为负，单位kWh',
    reward_kwh               DECIMAL(10,1) NOT NULL COMMENT '奖励电量，按0.1kWh向下截取，单位kWh',
    amount_fen               BIGINT        NOT NULL COMMENT '本次入账金额，单位分；ADJUSTMENT为差额，可为负',
    total_amount_fen         BIGINT        NOT NULL COMMENT '本版本重算的完整奖励金额，单位分',
    corrected_settlement_id  BIGINT        NULL COMMENT 'ADJUSTMENT指向的被更正账目ID；INITIAL为空',
    correction_reason        VARCHAR(255)  NULL COMMENT '更正缘由；INITIAL为空',
    request_id               VARCHAR(128)  NOT NULL COMMENT '结算请求幂等键',
    created_at               DATETIME(3)   NOT NULL COMMENT '入账时间UTC',
    -- request_id 全局唯一保证重放幂等；相同邀约、用户、基线/实际版本组合不得重复入账
    CONSTRAINT uk_settlement_request  UNIQUE (request_id),
    CONSTRAINT uk_settlement_versions UNIQUE (offer_id, user_id, baseline_version, actual_version)
);
