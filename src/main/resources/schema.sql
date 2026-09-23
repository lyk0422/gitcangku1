-- 频率协同试验台：仅使用 H2 内存库（MODE=MySQL），SQL 保持 MySQL 兼容写法。

CREATE TABLE IF NOT EXISTS spectrum_network (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '网络自增主键',
    network_id   VARCHAR(64)  NOT NULL COMMENT '业务网络ID，创建时指定，全局唯一',
    version      INT          NOT NULL COMMENT '当前网络版本号，创建为1，每次有效方案加一',
    created_at   BIGINT       NOT NULL COMMENT '创建时间，epoch 毫秒，UTC 时钟',
    updated_at   BIGINT       NOT NULL COMMENT '最近一次成功方案时间，epoch 毫秒',
    UNIQUE (network_id)
);

CREATE TABLE IF NOT EXISTS spectrum_station (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '台站自增主键',
    network_ref   BIGINT      NOT NULL COMMENT '所属 spectrum_network.id',
    station_id    VARCHAR(64) NOT NULL COMMENT '网络内唯一台站ID',
    budget        INT         NOT NULL COMMENT '干扰预算，0~1000 的整数，累计干扰等于该值仍合法',
    channel       INT         NOT NULL DEFAULT 0 COMMENT '当前频道，0 表示静默，1~8 为发射频道',
    UNIQUE (network_ref, station_id)
);

CREATE TABLE IF NOT EXISTS spectrum_edge (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '有向干扰边自增主键',
    network_ref   BIGINT      NOT NULL COMMENT '所属 spectrum_network.id',
    from_station  VARCHAR(64) NOT NULL COMMENT '干扰来源台站ID',
    to_station    VARCHAR(64) NOT NULL COMMENT '干扰指向台站ID，累计时方向不可倒置',
    amount        INT         NOT NULL COMMENT '该有向边的整数干扰量，0~1000，未定义边按 0 处理',
    UNIQUE (network_ref, from_station, to_station)
);

CREATE TABLE IF NOT EXISTS spectrum_plan (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '方案记录自增主键',
    network_ref     BIGINT      NOT NULL COMMENT '所属 spectrum_network.id',
    plan_key        VARCHAR(96) NOT NULL COMMENT '全局唯一方案键，同键重放不增加版本，换请求键复用则冲突',
    request_id      VARCHAR(96) NOT NULL COMMENT '首次成功提交该方案携带的全局请求ID',
    version         INT         NOT NULL COMMENT '该方案提交成功后的网络版本号',
    channels_before TEXT        NOT NULL COMMENT '提交前台站频道完整快照JSON，台站ID到频道(0静默)的映射',
    channels_after  TEXT        NOT NULL COMMENT '提交后完整网络频道快照JSON',
    summary         TEXT        NOT NULL COMMENT '提交后各发射接收台站同频道累计干扰汇总JSON',
    created_at      BIGINT      NOT NULL COMMENT '方案落库时间，epoch 毫秒',
    UNIQUE (plan_key)
);

CREATE TABLE IF NOT EXISTS spectrum_request (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '写请求幂等记录自增主键',
    request_id    VARCHAR(96) NOT NULL COMMENT '全局请求ID，仅成功请求占键，失败可复用原键重试',
    request_kind  VARCHAR(32) NOT NULL COMMENT '请求类型：CREATE_NETWORK / SUBMIT_PLAN',
    network_id    VARCHAR(64) COMMENT '关联网络ID，创建网络时记录新建网络',
    plan_key      VARCHAR(96) COMMENT '方案请求的 planKey，创建网络为空',
    param_digest  CHAR(64)    NOT NULL COMMENT '归一化参数摘要，同键异参返回409',
    http_status   INT         NOT NULL COMMENT '首次成功结果的HTTP状态码，重放原样返回',
    response_body TEXT        NOT NULL COMMENT '首次成功响应体JSON快照',
    created_at    BIGINT       NOT NULL COMMENT '首次成功时间，epoch 毫秒',
    UNIQUE (request_id)
);
