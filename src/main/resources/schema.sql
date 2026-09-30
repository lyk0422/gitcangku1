-- 发芽率证书业务建表脚本（H2 内存库 MODE=MySQL 下执行；字段注释语法同时兼容 H2 与 MySQL）

CREATE TABLE IF NOT EXISTS seed_batch (
    batch_no    VARCHAR(64)  NOT NULL COMMENT '种子批次编号，业务主键',
    crop_name   VARCHAR(128) NOT NULL COMMENT '作物名称',
    created_at  TIMESTAMP(6) NOT NULL COMMENT '批次入库时刻，UTC时间线',
    CONSTRAINT pk_seed_batch PRIMARY KEY (batch_no)
);
COMMENT ON TABLE seed_batch IS '种子批次表（合成批次数据）';

CREATE TABLE IF NOT EXISTS germination_test (
    test_no           VARCHAR(64)  NOT NULL COMMENT '检测编号，全库唯一；同一编号只能归属一个批次',
    batch_no          VARCHAR(64)  NOT NULL COMMENT '所属种子批次编号',
    sample_count      INT          NOT NULL COMMENT '样本数，单位粒，合法区间100-400',
    germinated_count  INT          NOT NULL COMMENT '发芽粒数，单位粒，0至样本数之间',
    status            VARCHAR(16)  NOT NULL COMMENT '状态：VALID有效，VOID已作废',
    recorded_at       TIMESTAMP(6) NOT NULL COMMENT '检测登记时刻，UTC时间线，复检按该时刻排序',
    voided_at         TIMESTAMP(6)     NULL COMMENT '作废时刻，UTC时间线；NULL表示未作废',
    CONSTRAINT pk_germination_test PRIMARY KEY (test_no),
    CONSTRAINT fk_germination_test_batch
        FOREIGN KEY (batch_no) REFERENCES seed_batch (batch_no),
    CONSTRAINT ck_germination_test_sample
        CHECK (sample_count BETWEEN 100 AND 400),
    CONSTRAINT ck_germination_test_germinated
        CHECK (germinated_count >= 0 AND germinated_count <= sample_count)
);
COMMENT ON TABLE germination_test IS '发芽率检测记录表（复检追加，历史不改写）';

CREATE INDEX IF NOT EXISTS idx_germination_test_batch
    ON germination_test (batch_no, recorded_at, test_no);

CREATE TABLE IF NOT EXISTS germination_certificate (
    cert_no        VARCHAR(64)    NOT NULL COMMENT '证书编号',
    batch_no       VARCHAR(64)    NOT NULL COMMENT '所属种子批次编号；同批次可有失效历史证书与新证书各一行',
    first_test_no  VARCHAR(64)    NOT NULL COMMENT '固化的第一次（较早）检测编号',
    first_ratio    DECIMAL(5, 2)  NOT NULL COMMENT '第一次检测发芽率，单位%，两位小数',
    second_test_no VARCHAR(64)    NOT NULL COMMENT '固化的第二次（最近）检测编号',
    second_ratio   DECIMAL(5, 2)  NOT NULL COMMENT '第二次检测发芽率，单位%，两位小数',
    status         VARCHAR(16)    NOT NULL COMMENT '状态：VALID有效（含已放行留存快照），INVALID未放行即失效',
    issued_at      TIMESTAMP(6)   NOT NULL COMMENT '签发时刻，UTC时间线',
    CONSTRAINT pk_germination_certificate PRIMARY KEY (cert_no),
    CONSTRAINT fk_certificate_batch
        FOREIGN KEY (batch_no) REFERENCES seed_batch (batch_no),
    CONSTRAINT fk_certificate_first_test
        FOREIGN KEY (first_test_no) REFERENCES germination_test (test_no),
    CONSTRAINT fk_certificate_second_test
        FOREIGN KEY (second_test_no) REFERENCES germination_test (test_no)
);
COMMENT ON TABLE germination_certificate IS '发芽率证书表（签发即固化，不修改历史证书内容）';

CREATE INDEX IF NOT EXISTS idx_certificate_batch
    ON germination_certificate (batch_no, issued_at);

CREATE TABLE IF NOT EXISTS batch_release (
    batch_no     VARCHAR(64)  NOT NULL COMMENT '已放行种子批次编号，一个批次至多放行一次',
    cert_no      VARCHAR(64)  NOT NULL COMMENT '放行时依据的证书编号，保留证书快照关联',
    released_at  TIMESTAMP(6) NOT NULL COMMENT '放行时刻，UTC时间线；放行事实不回滚',
    CONSTRAINT pk_batch_release PRIMARY KEY (batch_no),
    CONSTRAINT fk_release_batch
        FOREIGN KEY (batch_no) REFERENCES seed_batch (batch_no),
    CONSTRAINT fk_release_certificate
        FOREIGN KEY (cert_no) REFERENCES germination_certificate (cert_no)
);
COMMENT ON TABLE batch_release IS '批次放行事实表';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id    VARCHAR(128) NOT NULL COMMENT '幂等请求号，全库唯一',
    operation     VARCHAR(32)  NOT NULL COMMENT '操作类型：REGISTER_TEST/VOID_TEST/ISSUE_CERTIFICATE/RELEASE_BATCH',
    params_hash   VARCHAR(64)  NOT NULL COMMENT '请求参数规范化哈希，异参重放据此返回409',
    status        VARCHAR(16)  NOT NULL COMMENT '处理状态：PROCESSING处理中，DONE完成',
    response_json CLOB             NULL COMMENT '首次成功响应的JSON快照，同参重放原样返回',
    created_at    TIMESTAMP(6) NOT NULL COMMENT '请求首次到达时刻，UTC时间线',
    CONSTRAINT pk_idempotent_request PRIMARY KEY (request_id)
);
COMMENT ON TABLE idempotent_request IS '幂等请求记录表（随业务事务提交或回滚，失败可用原键重试）';
