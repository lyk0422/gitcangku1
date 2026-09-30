-- 种子发芽率证书业务表结构。本地与测试使用 H2 内存库 MODE=MySQL 执行。
-- 检测编号全库唯一，从数据库层面保证同一检测编号只能归属一个批次。

CREATE TABLE seed_batch (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no    VARCHAR(64)  NOT NULL,
    crop_name   VARCHAR(128) NOT NULL,
    created_at  DATETIME     NOT NULL,
    CONSTRAINT uk_seed_batch_no UNIQUE (batch_no)
);
COMMENT ON TABLE seed_batch IS '种子批次表';
COMMENT ON COLUMN seed_batch.id IS '主键';
COMMENT ON COLUMN seed_batch.batch_no IS '批次编号，业务唯一';
COMMENT ON COLUMN seed_batch.crop_name IS '作物名称';
COMMENT ON COLUMN seed_batch.created_at IS '创建时刻，Asia/Shanghai';

CREATE TABLE germination_test (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    test_no           VARCHAR(64) NOT NULL,
    batch_id          BIGINT      NOT NULL,
    sample_size       INT         NOT NULL,
    germinated_count  INT         NOT NULL,
    status            VARCHAR(16) NOT NULL,
    tested_at         DATETIME    NOT NULL,
    voided_at         DATETIME    NULL,
    created_at        DATETIME    NOT NULL,
    CONSTRAINT uk_germination_test_no UNIQUE (test_no),
    CONSTRAINT fk_germination_test_batch FOREIGN KEY (batch_id) REFERENCES seed_batch (id),
    CONSTRAINT ck_germination_test_status CHECK (status IN ('VALID', 'VOID')),
    CONSTRAINT ck_germination_test_sample CHECK (sample_size BETWEEN 100 AND 400),
    CONSTRAINT ck_germination_test_count CHECK (germinated_count BETWEEN 0 AND sample_size)
);
COMMENT ON TABLE germination_test IS '发芽率检测记录表，复检追加新行，历史行不覆盖';
COMMENT ON COLUMN germination_test.id IS '主键';
COMMENT ON COLUMN germination_test.test_no IS '检测编号，全库唯一，同一编号只能归属一个批次';
COMMENT ON COLUMN germination_test.batch_id IS '所属批次主键';
COMMENT ON COLUMN germination_test.sample_size IS '样本数，单位粒，合法范围100-400';
COMMENT ON COLUMN germination_test.germinated_count IS '发芽粒数，单位粒，不得大于样本数';
COMMENT ON COLUMN germination_test.status IS '状态：VALID有效，VOID已作废';
COMMENT ON COLUMN germination_test.tested_at IS '检测时刻，Asia/Shanghai，决定最近两次的次序';
COMMENT ON COLUMN germination_test.voided_at IS '作废时刻，Asia/Shanghai；未作废为NULL';
COMMENT ON COLUMN germination_test.created_at IS '入库时刻，Asia/Shanghai';

CREATE TABLE germination_certificate (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    certificate_no          VARCHAR(64)    NOT NULL,
    batch_id                BIGINT         NOT NULL,
    first_test_no           VARCHAR(64)    NOT NULL,
    first_sample_size       INT            NOT NULL,
    first_germinated_count  INT            NOT NULL,
    first_ratio             DECIMAL(6, 4)  NOT NULL,
    second_test_no          VARCHAR(64)    NOT NULL,
    second_sample_size      INT            NOT NULL,
    second_germinated_count INT            NOT NULL,
    second_ratio            DECIMAL(6, 4)  NOT NULL,
    status                  VARCHAR(16)    NOT NULL,
    request_id              VARCHAR(64)    NOT NULL,
    issued_at               DATETIME       NOT NULL,
    created_at              DATETIME       NOT NULL,
    CONSTRAINT uk_germination_certificate_no UNIQUE (certificate_no),
    CONSTRAINT fk_germination_certificate_batch FOREIGN KEY (batch_id) REFERENCES seed_batch (id),
    CONSTRAINT ck_germination_certificate_status CHECK (status IN ('VALID', 'INVALID'))
);
COMMENT ON TABLE germination_certificate IS '发芽率证书表，签发时固化两次检测编号、样本数、发芽粒数、比例与签发时刻';
COMMENT ON COLUMN germination_certificate.id IS '主键';
COMMENT ON COLUMN germination_certificate.certificate_no IS '证书编号，业务唯一';
COMMENT ON COLUMN germination_certificate.batch_id IS '所属批次主键';
COMMENT ON COLUMN germination_certificate.first_test_no IS '第一次（较早一次）检测编号快照';
COMMENT ON COLUMN germination_certificate.first_sample_size IS '第一次检测样本数快照，单位粒';
COMMENT ON COLUMN germination_certificate.first_germinated_count IS '第一次检测发芽粒数快照，单位粒';
COMMENT ON COLUMN germination_certificate.first_ratio IS '第一次检测发芽率快照，0-1之间四位小数';
COMMENT ON COLUMN germination_certificate.second_test_no IS '第二次（最近一次）检测编号快照';
COMMENT ON COLUMN germination_certificate.second_sample_size IS '第二次检测样本数快照，单位粒';
COMMENT ON COLUMN germination_certificate.second_germinated_count IS '第二次检测发芽粒数快照，单位粒';
COMMENT ON COLUMN germination_certificate.second_ratio IS '第二次检测发芽率快照，0-1之间四位小数';
COMMENT ON COLUMN germination_certificate.status IS '状态：VALID有效可放行，INVALID依赖检测作废且未放行而失效';
COMMENT ON COLUMN germination_certificate.request_id IS '签发请求幂等键';
COMMENT ON COLUMN germination_certificate.issued_at IS '签发时刻，Asia/Shanghai';
COMMENT ON COLUMN germination_certificate.created_at IS '入库时刻，Asia/Shanghai';

CREATE TABLE batch_release (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id        BIGINT         NOT NULL,
    certificate_id  BIGINT         NOT NULL,
    certificate_no  VARCHAR(64)    NOT NULL,
    first_test_no   VARCHAR(64)    NOT NULL,
    first_ratio     DECIMAL(6, 4)  NOT NULL,
    second_test_no  VARCHAR(64)    NOT NULL,
    second_ratio    DECIMAL(6, 4)  NOT NULL,
    request_id      VARCHAR(64)    NOT NULL,
    released_at     DATETIME       NOT NULL,
    created_at      DATETIME       NOT NULL,
    CONSTRAINT uk_batch_release_batch UNIQUE (batch_id),
    CONSTRAINT fk_batch_release_batch FOREIGN KEY (batch_id) REFERENCES seed_batch (id),
    CONSTRAINT fk_batch_release_certificate FOREIGN KEY (certificate_id) REFERENCES germination_certificate (id)
);
COMMENT ON TABLE batch_release IS '批次放行事实表，一批次至多一行，放行后不回滚';
COMMENT ON COLUMN batch_release.id IS '主键';
COMMENT ON COLUMN batch_release.batch_id IS '所属批次主键，全库唯一，一批次只放行一次';
COMMENT ON COLUMN batch_release.certificate_id IS '放行所依据证书主键';
COMMENT ON COLUMN batch_release.certificate_no IS '放行所依据证书编号快照';
COMMENT ON COLUMN batch_release.first_test_no IS '证书第一次检测编号快照';
COMMENT ON COLUMN batch_release.first_ratio IS '证书第一次检测发芽率快照，0-1之间四位小数';
COMMENT ON COLUMN batch_release.second_test_no IS '证书第二次检测编号快照';
COMMENT ON COLUMN batch_release.second_ratio IS '证书第二次检测发芽率快照，0-1之间四位小数';
COMMENT ON COLUMN batch_release.request_id IS '放行请求幂等键';
COMMENT ON COLUMN batch_release.released_at IS '放行时刻，Asia/Shanghai';
COMMENT ON COLUMN batch_release.created_at IS '入库时刻，Asia/Shanghai';

CREATE TABLE idempotency_record (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    scope         VARCHAR(16)  NOT NULL,
    request_id    VARCHAR(64)  NOT NULL,
    fingerprint   VARCHAR(255) NOT NULL,
    response_json CLOB         NOT NULL,
    created_at    DATETIME     NOT NULL,
    CONSTRAINT uk_idempotency_key UNIQUE (scope, request_id),
    CONSTRAINT ck_idempotency_scope CHECK (scope IN ('ISSUE', 'RELEASE'))
);
COMMENT ON TABLE idempotency_record IS '幂等记录表，仅记录首次成功请求；事务失败不留记录，允许原键重试';
COMMENT ON COLUMN idempotency_record.id IS '主键';
COMMENT ON COLUMN idempotency_record.scope IS '作用域：ISSUE签发，RELEASE放行';
COMMENT ON COLUMN idempotency_record.request_id IS '请求幂等键，与作用域联合唯一';
COMMENT ON COLUMN idempotency_record.fingerprint IS '请求参数指纹，本题为批次编号，异参重放据此返回409';
COMMENT ON COLUMN idempotency_record.response_json IS '首次成功响应JSON快照，重放原样返回';
COMMENT ON COLUMN idempotency_record.created_at IS '记录时刻，Asia/Shanghai';
