-- 试验台站频率协同：H2 内存库（MODE=MySQL）自动建表脚本。
-- 仅在同一 JVM 生命周期内保留数据；字符集 UTF-8，所有金额/数量类字段均为整数。

-- 频率协同网络：网络配置（台站、边）创建后不可修改，仅频率方案可推进版本。
CREATE TABLE IF NOT EXISTS spectrum_network (
    network_id   VARCHAR(64)  NOT NULL PRIMARY KEY,
    name         VARCHAR(128),
    version      INT          NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);
COMMENT ON TABLE spectrum_network IS '频率协同网络表，版本号从1开始，随成功方案单调递增';
COMMENT ON COLUMN spectrum_network.version IS '当前配置版本号，初始为1，每次成功提交（含无变化方案）加一';

-- 台站：干扰预算为 0~1000 的整数；channel 为当前频道，0 表示静默（初始全部静默），有效值 0~8。
CREATE TABLE IF NOT EXISTS spectrum_station (
    network_id           VARCHAR(64) NOT NULL,
    station_id           VARCHAR(64) NOT NULL,
    position             INT         NOT NULL,
    interference_budget  INT         NOT NULL,
    channel              INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (network_id, station_id)
);
COMMENT ON COLUMN spectrum_station.interference_budget IS '台站可承受的累计同频干扰量预算，整数，量纲无量纲，范围0~1000';
COMMENT ON COLUMN spectrum_station.channel IS '当前频道号：0表示静默（不发射不参与校验），1~8为正常频道';
COMMENT ON COLUMN spectrum_station.position IS '建网时台站的定义顺序，用于稳定输出排序';

-- 有向干扰边：from->to 表示发射台站 from 对接收台站 to 的同频干扰量；禁止自环与重复边，未定义边视为0。
CREATE TABLE IF NOT EXISTS spectrum_edge (
    network_id       VARCHAR(64) NOT NULL,
    from_station_id  VARCHAR(64) NOT NULL,
    to_station_id    VARCHAR(64) NOT NULL,
    interference     INT         NOT NULL,
    PRIMARY KEY (network_id, from_station_id, to_station_id)
);
COMMENT ON COLUMN spectrum_edge.interference IS '有向干扰量，整数，范围0~1000，仅同频道时计入接收台站累计值';

-- 成功方案记录（不可变历史）：失败（超预算等）不写入，方案记录完全不被失败提交改变。
CREATE TABLE IF NOT EXISTS spectrum_plan (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    network_id            VARCHAR(64) NOT NULL,
    plan_key              VARCHAR(64) NOT NULL,
    request_id            VARCHAR(64) NOT NULL,
    version_from          INT         NOT NULL,
    version_to            INT         NOT NULL,
    normalized_request    CLOB        NOT NULL,
    before_config         CLOB        NOT NULL,
    after_config          CLOB        NOT NULL,
    interference_summary  CLOB        NOT NULL,
    created_at            TIMESTAMP   NOT NULL,
    CONSTRAINT uk_plan_key UNIQUE (plan_key),
    CONSTRAINT uk_plan_request_id UNIQUE (request_id)
);
COMMENT ON TABLE spectrum_plan IS '成功频率方案的不可变历史，含完整前后配置与干扰汇总';
COMMENT ON COLUMN spectrum_plan.plan_key IS '全局唯一方案键，成功后占用；异请求复用返回409，失败不占键';
COMMENT ON COLUMN spectrum_plan.version_from IS '提交前的网络版本号';
COMMENT ON COLUMN spectrum_plan.version_to IS '提交后的网络版本号（version_from+1）';

-- 写操作幂等记录表：仅记录成功结果（2xx）；参数非法/未找到/冲突/超预算均失败不占键。
CREATE TABLE IF NOT EXISTS spectrum_request (
    request_id       VARCHAR(64) NOT NULL PRIMARY KEY,
    operation        VARCHAR(32) NOT NULL,
    target_network   VARCHAR(64),
    params_hash      CHAR(64)    NOT NULL,
    result_status    INT         NOT NULL,
    result_body      CLOB        NOT NULL,
    created_at       TIMESTAMP   NOT NULL
);
COMMENT ON TABLE spectrum_request IS '写操作requestId幂等表：同键同参返回首次结果，同键异参409，失败不占键';
COMMENT ON COLUMN spectrum_request.params_hash IS '规范化参数（台站/方案集合按ID排序）的SHA-256，集合换序视为同参';
