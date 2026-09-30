-- 馆藏影片
CREATE TABLE IF NOT EXISTS film (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '影片主键',
    code             VARCHAR(64)  NOT NULL COMMENT '影片业务编号，全局唯一',
    planned_segments INT          NOT NULL COMMENT '计划片段总数；审核时每段都必须有有效提交',
    created_at       TIMESTAMP(3) NOT NULL COMMENT '创建时间，UTC',
    CONSTRAINT pk_film PRIMARY KEY (id),
    CONSTRAINT uk_film_code UNIQUE (code)
) COMMENT = '馆藏影片，片段按 1..planned_segments 序号组成整片';

-- 片段修复工作版（只追加的版次链）
CREATE TABLE IF NOT EXISTS segment_version (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '版本记录主键',
    film_id          BIGINT       NOT NULL COMMENT '所属影片 ID',
    segment_no       INT          NOT NULL COMMENT '片段序号，从 1 开始',
    version_no       INT          NOT NULL COMMENT '片段内版次，首版为 1，逐次加 1',
    content_digest   VARCHAR(80)  NOT NULL COMMENT '内容摘要，格式 sha256: 加 64 位十六进制',
    based_on_version INT          NULL COMMENT '基于的当前版次；首版为空，续版必须等于提交前最新版次',
    request_id       VARCHAR(80)  NOT NULL COMMENT '客户端幂等请求号',
    created_at       TIMESTAMP(3) NOT NULL COMMENT '提交时间，UTC',
    CONSTRAINT pk_segment_version PRIMARY KEY (id),
    CONSTRAINT uk_segment_request UNIQUE (film_id, request_id),
    CONSTRAINT uk_segment_version UNIQUE (film_id, segment_no, version_no)
) COMMENT = '片段修复提交记录，只追加不改写，按版次组成版本链';

-- 不可变母版
CREATE TABLE IF NOT EXISTS master (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '母版主键',
    film_id           BIGINT       NOT NULL COMMENT '所属影片 ID',
    master_no         VARCHAR(80)  NOT NULL COMMENT '不可变母版编号，全局唯一，交付只引用此编号',
    review_request_id VARCHAR(80)  NOT NULL COMMENT '整片审核的幂等请求号',
    status            VARCHAR(16)  NOT NULL COMMENT '母版状态：APPROVED-审核通过可交付；REVOKED-已撤销仅阻止新交付',
    created_at        TIMESTAMP(3) NOT NULL COMMENT '审核通过时间，UTC',
    revoked_at        TIMESTAMP(3) NULL COMMENT '撤销时间，UTC；未撤销为空',
    CONSTRAINT pk_master PRIMARY KEY (id),
    CONSTRAINT uk_master_no UNIQUE (master_no),
    CONSTRAINT uk_master_review_request UNIQUE (film_id, review_request_id)
) COMMENT = '整片审核生成的固定母版，编号与内容不可变';

-- 母版固定的片段引用（审核瞬间快照）
CREATE TABLE IF NOT EXISTS master_segment (
    master_id      BIGINT      NOT NULL COMMENT '所属母版 ID',
    segment_no     INT         NOT NULL COMMENT '片段序号',
    version_no     INT         NOT NULL COMMENT '固定的片段版次',
    content_digest VARCHAR(80) NOT NULL COMMENT '固定的片段内容摘要',
    CONSTRAINT pk_master_segment PRIMARY KEY (master_id, segment_no)
) COMMENT = '母版片段快照，按序号固定版次与摘要，不读取后来更新的工作版';

-- 交付回执
CREATE TABLE IF NOT EXISTS delivery_receipt (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '回执主键',
    film_id       BIGINT       NOT NULL COMMENT '所属影片 ID',
    receipt_no    VARCHAR(80)  NOT NULL COMMENT '回执编号，全局唯一；重试不生成第二张',
    master_no     VARCHAR(80)  NOT NULL COMMENT '交付引用的不可变母版编号',
    request_id    VARCHAR(80)  NOT NULL COMMENT '交付的客户端幂等请求号',
    snapshot_json TEXT         NOT NULL COMMENT '交付瞬间母版片段快照 JSON；母版撤销后仍保留',
    created_at    TIMESTAMP(3) NOT NULL COMMENT '交付时间，UTC',
    CONSTRAINT pk_delivery_receipt PRIMARY KEY (id),
    CONSTRAINT uk_receipt_no UNIQUE (receipt_no),
    CONSTRAINT uk_delivery_request UNIQUE (film_id, request_id)
) COMMENT = '交付回执，保存曾交付母版的快照，不可变';
