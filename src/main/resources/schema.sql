-- 影片修复版本管理：H2(MODE=MySQL) 与 MySQL 8 均兼容的建表脚本
-- 表与字段均带中文注释，说明状态、单位、时区与空值含义

CREATE TABLE IF NOT EXISTS film (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    code             VARCHAR(64)  NOT NULL COMMENT '影片编号，业务唯一',
    title            VARCHAR(200) NOT NULL COMMENT '影片标题',
    planned_segments INT          NOT NULL COMMENT '计划片段总数，无单位，序号从1开始',
    created_at       DATETIME     NOT NULL COMMENT '建档时间，Asia/Shanghai时区',
    UNIQUE (code)
) COMMENT '馆藏影片，片段按序号组成影片';

CREATE TABLE IF NOT EXISTS segment_version (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    film_id    BIGINT       NOT NULL COMMENT '所属影片ID',
    seq        INT          NOT NULL COMMENT '片段序号，从1开始',
    version_no INT          NOT NULL COMMENT '版次，从1开始递增，只追加不修改',
    digest     VARCHAR(128) NOT NULL COMMENT '内容摘要，4-128位字母数字及._:-',
    request_id VARCHAR(64)  NOT NULL COMMENT '提交请求ID（幂等键），全局唯一',
    created_at DATETIME     NOT NULL COMMENT '提交时间，Asia/Shanghai时区',
    UNIQUE (film_id, seq, version_no),
    UNIQUE (request_id)
) COMMENT '片段修复工作版，append-only版次链';

CREATE TABLE IF NOT EXISTS review_request (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    film_id    BIGINT      NOT NULL COMMENT '所属影片ID',
    request_id VARCHAR(64) NOT NULL COMMENT '审核请求ID（幂等键），全局唯一',
    status     VARCHAR(16) NOT NULL COMMENT '状态：PENDING待审/APPROVED通过/EXPIRED过期/REVOKED撤销',
    master_id  BIGINT      NULL     COMMENT '通过后生成的母版ID；未通过或过期为NULL',
    created_at DATETIME    NOT NULL COMMENT '审核请求创建时间，Asia/Shanghai时区',
    decided_at DATETIME    NULL     COMMENT '通过或撤销时间；未决定为NULL',
    UNIQUE (request_id)
) COMMENT '整片审核请求，创建时固定片段基线';

CREATE TABLE IF NOT EXISTS review_segment_baseline (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    review_id  BIGINT       NOT NULL COMMENT '所属审核请求ID',
    seq        INT          NOT NULL COMMENT '片段序号，从1开始',
    version_no INT          NOT NULL COMMENT '基线固定的片段版次',
    digest     VARCHAR(128) NOT NULL COMMENT '基线固定的内容摘要快照',
    fixed_at   DATETIME     NOT NULL COMMENT '基线固定时间，Asia/Shanghai时区',
    UNIQUE (review_id, seq)
) COMMENT '审核基线：审核瞬间按序号固定的版次与摘要';

CREATE TABLE IF NOT EXISTS master (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    master_no     VARCHAR(80) NOT NULL COMMENT '不可变母版编号，业务唯一',
    film_id       BIGINT      NOT NULL COMMENT '所属影片ID',
    review_id     BIGINT      NOT NULL COMMENT '来源审核请求ID',
    segment_count INT         NOT NULL COMMENT '母版固定的片段总数，无单位',
    created_at    DATETIME    NOT NULL COMMENT '母版生成时间，Asia/Shanghai时区',
    UNIQUE (master_no),
    UNIQUE (review_id)
) COMMENT '审核通过生成的不可变固定母版';

CREATE TABLE IF NOT EXISTS master_segment (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    master_id  BIGINT       NOT NULL COMMENT '所属母版ID',
    seq        INT          NOT NULL COMMENT '片段序号，从1开始',
    version_no INT          NOT NULL COMMENT '母版固定的片段版次',
    digest     VARCHAR(128) NOT NULL COMMENT '母版固定的内容摘要快照，不随后续工作版变化',
    UNIQUE (master_id, seq)
) COMMENT '母版片段快照，一经写入永不更新';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id   VARCHAR(64)  NOT NULL COMMENT '幂等请求ID',
    operation    VARCHAR(24)  NOT NULL COMMENT '操作类型：SEGMENT_SUBMIT片段提交/REVIEW_CREATE审核/DELIVERY交付',
    film_id      BIGINT       NULL     COMMENT '所属影片ID；无影片上下文时为NULL',
    payload_hash CHAR(64)     NOT NULL COMMENT '规范化参数的SHA-256摘要，用于同ID异参判定',
    result_ref   VARCHAR(64)  NULL     COMMENT '首次成功结果引用（版次行ID/审核ID/回执ID）',
    created_at   DATETIME     NOT NULL COMMENT '首次受理时间，Asia/Shanghai时区',
    PRIMARY KEY (request_id, operation)
) COMMENT '幂等请求记录';

CREATE TABLE IF NOT EXISTS delivery_receipt (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id      VARCHAR(64)  NOT NULL COMMENT '交付请求ID（幂等键），全局唯一',
    film_id         BIGINT       NOT NULL COMMENT '所属影片ID',
    master_id       BIGINT       NOT NULL COMMENT '引用的母版ID',
    master_no       VARCHAR(80)  NOT NULL COMMENT '引用的母版编号快照',
    snapshot_digest VARCHAR(4000) NOT NULL COMMENT '交付时母版全部片段摘要定格：序号:版次:摘要按序号拼接',
    created_at      DATETIME     NOT NULL COMMENT '交付时间，Asia/Shanghai时区',
    UNIQUE (request_id)
) COMMENT '交付回执，保存曾交付的母版快照，撤销审核不删除';
