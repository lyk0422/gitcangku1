-- 海洋盐度采样业务建表脚本（H2 内存库，MODE=MySQL）。
-- 仅在默认 H2 本地/测试环境自动执行；外部 MySQL 不由本脚本初始化。
-- 全部约束内联定义，配合 CREATE TABLE IF NOT EXISTS 可在同一内存库上安全重复执行。

-- 航次表：code 为航次业务编号；status 取值 OPEN（采样中）/SEALED（已封存）。
CREATE TABLE IF NOT EXISTS voyages (
    code           VARCHAR(64)  NOT NULL CONSTRAINT pk_voyages PRIMARY KEY,
    status         VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    sealed_at_utc  TIMESTAMP    NULL,
    created_at_utc TIMESTAMP    NOT NULL
);
COMMENT ON TABLE  voyages IS '航次主表，状态 OPEN 可采样与质控，SEALED 后剖面冻结';
COMMENT ON COLUMN voyages.code IS '航次业务编号，业务主键';
COMMENT ON COLUMN voyages.status IS '航次状态：OPEN-采样中，SEALED-已封存';
COMMENT ON COLUMN voyages.sealed_at_utc IS '封存完成时刻（UTC），未封存为 NULL';
COMMENT ON COLUMN voyages.created_at_utc IS '航次首份样本提交时的创建时刻（UTC）';

-- 原始样本表：同一航次/站位/深度可有多条重测版本，但 adopted_slot 唯一索引保证同一位置至多一条采纳版本。
CREATE TABLE IF NOT EXISTS samples (
    id               BIGINT       NOT NULL AUTO_INCREMENT CONSTRAINT pk_samples PRIMARY KEY,
    voyage_code      VARCHAR(64)  NOT NULL,
    station          VARCHAR(64)  NOT NULL,
    depth_m          INT          NOT NULL,
    salinity_psu     DECIMAL(6,3) NOT NULL,
    collected_at_utc TIMESTAMP    NOT NULL,
    sample_no        VARCHAR(96)  NOT NULL CONSTRAINT uk_samples_sample_no UNIQUE,
    request_id       VARCHAR(96)  NOT NULL CONSTRAINT uk_samples_request_id UNIQUE,
    adopted          BOOLEAN      NOT NULL DEFAULT FALSE,
    version_no       INT          NOT NULL,
    created_at_utc   TIMESTAMP    NOT NULL,
    CONSTRAINT uk_samples_position_version UNIQUE (voyage_code, station, depth_m, version_no),
    -- 仅采纳行生成“航次|站位|深度”槽位，未采纳行为 NULL；唯一索引对多个 NULL 不冲突，
    -- 从而在数据库层强制每个位置至多一条采纳版本。
    adopted_slot     VARCHAR(200) GENERATED ALWAYS AS
                        (CASE WHEN adopted THEN CONCAT(voyage_code, '|', station, '|', CAST(depth_m AS VARCHAR(10)))
                              ELSE NULL END)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_samples_adopted_slot ON samples (adopted_slot);
CREATE INDEX IF NOT EXISTS ix_samples_position ON samples (voyage_code, station, depth_m);
COMMENT ON TABLE  samples IS '盐度原始样本表，含重测版本与采纳标记，原始重测永久保留';
COMMENT ON COLUMN samples.id IS '样本自增主键';
COMMENT ON COLUMN samples.voyage_code IS '所属航次编号';
COMMENT ON COLUMN samples.station IS '站位编号';
COMMENT ON COLUMN samples.depth_m IS '采样深度，单位米，允许范围 0-500';
COMMENT ON COLUMN samples.salinity_psu IS '盐度，单位 PSU，允许范围 0-45';
COMMENT ON COLUMN samples.collected_at_utc IS '样本采集时刻（UTC）';
COMMENT ON COLUMN samples.sample_no IS '采样编号，全局唯一，重复编号内容不同返回 409';
COMMENT ON COLUMN samples.request_id IS '客户端请求幂等键，全局唯一，同参重放返回原样本';
COMMENT ON COLUMN samples.adopted IS '是否为质控员当前采纳版本：TRUE-采纳，FALSE-原始留存';
COMMENT ON COLUMN samples.version_no IS '同一航次/站位/深度位置上的重测版本号，从 1 开始';
COMMENT ON COLUMN samples.created_at_utc IS '样本入库时刻（UTC）';
COMMENT ON COLUMN samples.adopted_slot IS '采纳槽位生成列：采纳行=航次|站位|深度，未采纳行=NULL，配合唯一索引保证单采纳';

-- 封存剖面表：封存时按站位、深度升序固化的采纳快照，引用样本原始主键，封存后不可变。
CREATE TABLE IF NOT EXISTS sealed_profiles (
    id               BIGINT       NOT NULL AUTO_INCREMENT CONSTRAINT pk_sealed_profiles PRIMARY KEY,
    voyage_code      VARCHAR(64)  NOT NULL,
    station          VARCHAR(64)  NOT NULL,
    depth_m          INT          NOT NULL,
    sample_id        BIGINT       NOT NULL,
    sample_no        VARCHAR(96)  NOT NULL,
    salinity_psu     DECIMAL(6,3) NOT NULL,
    collected_at_utc TIMESTAMP    NOT NULL,
    sealed_at_utc    TIMESTAMP    NOT NULL,
    CONSTRAINT uk_sealed_position UNIQUE (voyage_code, station, depth_m)
);
CREATE INDEX IF NOT EXISTS ix_sealed_voyage ON sealed_profiles (voyage_code, station, depth_m);
COMMENT ON TABLE  sealed_profiles IS '封存盐度剖面快照，按站位与深度升序固化，封存后不可变';
COMMENT ON COLUMN sealed_profiles.id IS '剖面条目自增主键';
COMMENT ON COLUMN sealed_profiles.voyage_code IS '所属航次编号';
COMMENT ON COLUMN sealed_profiles.station IS '站位编号';
COMMENT ON COLUMN sealed_profiles.depth_m IS '采样深度，单位米，封存深度固定为 0/50/100';
COMMENT ON COLUMN sealed_profiles.sample_id IS '被固化样本的主键，引用 samples.id，保留可追溯关联';
COMMENT ON COLUMN sealed_profiles.sample_no IS '被固化样本的采样编号快照';
COMMENT ON COLUMN sealed_profiles.salinity_psu IS '被固化盐度快照，单位 PSU';
COMMENT ON COLUMN sealed_profiles.collected_at_utc IS '被固化样本的采集时刻（UTC）';
COMMENT ON COLUMN sealed_profiles.sealed_at_utc IS '封存固化时刻（UTC）';

-- 质控采纳记录表：记录每次采纳操作的幂等键与目标版本，重放同参返回原结果，键复用但目标不同返回 409。
CREATE TABLE IF NOT EXISTS adoptions (
    id             BIGINT       NOT NULL AUTO_INCREMENT CONSTRAINT pk_adoptions PRIMARY KEY,
    request_id     VARCHAR(96)  NOT NULL CONSTRAINT uk_adoptions_request_id UNIQUE,
    voyage_code    VARCHAR(64)  NOT NULL,
    station        VARCHAR(64)  NOT NULL,
    depth_m        INT          NOT NULL,
    sample_id      BIGINT       NOT NULL,
    adopted_at_utc TIMESTAMP    NOT NULL
);
COMMENT ON TABLE  adoptions IS '质控采纳操作记录，保证质控请求幂等与可重放';
COMMENT ON COLUMN adoptions.id IS '采纳记录自增主键';
COMMENT ON COLUMN adoptions.request_id IS '质控请求幂等键，全局唯一';
COMMENT ON COLUMN adoptions.voyage_code IS '质控操作所属航次编号';
COMMENT ON COLUMN adoptions.station IS '采纳版本所在站位';
COMMENT ON COLUMN adoptions.depth_m IS '采纳版本所在深度，单位米';
COMMENT ON COLUMN adoptions.sample_id IS '被采纳样本主键，引用 samples.id';
COMMENT ON COLUMN adoptions.adopted_at_utc IS '采纳操作时刻（UTC）';

-- 勘误表：封存后只允许追加引用原封存条目的更正说明，原剖面继续可查，不改冻结数据。
CREATE TABLE IF NOT EXISTS corrections (
    id                      BIGINT       NOT NULL AUTO_INCREMENT CONSTRAINT pk_corrections PRIMARY KEY,
    voyage_code             VARCHAR(64)  NOT NULL,
    station                 VARCHAR(64)  NOT NULL,
    depth_m                 INT          NOT NULL,
    sealed_profile_id       BIGINT       NOT NULL,
    corrected_salinity_psu  DECIMAL(6,3) NULL,
    note                    VARCHAR(500) NOT NULL,
    request_id              VARCHAR(96)  NOT NULL CONSTRAINT uk_corrections_request_id UNIQUE,
    created_at_utc          TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_corrections_voyage ON corrections (voyage_code, station, depth_m);
COMMENT ON TABLE  corrections IS '封存后的勘误说明，仅追加，不修改冻结剖面';
COMMENT ON COLUMN corrections.id IS '勘误自增主键';
COMMENT ON COLUMN corrections.voyage_code IS '所属航次编号';
COMMENT ON COLUMN corrections.station IS '站位编号';
COMMENT ON COLUMN corrections.depth_m IS '勘误对应的深度，单位米';
COMMENT ON COLUMN corrections.sealed_profile_id IS '被勘误的原封存剖面条目主键，引用 sealed_profiles.id';
COMMENT ON COLUMN corrections.corrected_salinity_psu IS '更正后的盐度，单位 PSU；仅作说明记录，NULL 表示纯文字勘误';
COMMENT ON COLUMN corrections.note IS '更正说明正文';
COMMENT ON COLUMN corrections.request_id IS '勘误请求幂等键，全局唯一，同参重放返回原勘误';
COMMENT ON COLUMN corrections.created_at_utc IS '勘误追加时刻（UTC）';
