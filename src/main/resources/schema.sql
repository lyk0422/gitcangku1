-- 档案脱酸处理流程建表脚本（本地与测试均在 H2 内存库 MODE=MySQL 下执行）。

CREATE TABLE IF NOT EXISTS archive_page (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    accession_no VARCHAR(64)  NOT NULL COMMENT '馆藏号，全局唯一',
    created_at   DATETIME(6)  NOT NULL COMMENT '登记时间，业务时区 Asia/Shanghai',
    CONSTRAINT uk_archive_page_accession UNIQUE (accession_no)
);

CREATE TABLE IF NOT EXISTS deacidification_batch (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no   VARCHAR(64)  NOT NULL COMMENT '脱酸批次号，全局唯一',
    status     VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT '批次状态：OPEN 进行中；REJECTED 质检退回（可追加处理轮次）；SEALED 已封存只读',
    sealed_at  DATETIME(6)  NULL COMMENT '封存时间，时区 Asia/Shanghai；NULL 表示尚未封存',
    created_at DATETIME(6)  NOT NULL COMMENT '建批时间，时区 Asia/Shanghai',
    CONSTRAINT uk_deacidification_batch_no UNIQUE (batch_no)
);

CREATE TABLE IF NOT EXISTS batch_member (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id      BIGINT       NOT NULL COMMENT '所属脱酸批次ID',
    page_id       BIGINT       NOT NULL COMMENT '档案页ID',
    member_status VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '在组状态：ACTIVE 在组；RELEASED 未封存批次已归还出库',
    joined_at     DATETIME(6)  NOT NULL COMMENT '入组时间，时区 Asia/Shanghai',
    released_at   DATETIME(6)  NULL COMMENT '从未封存批次归还的时间；NULL 表示仍在组；封存后归还不改本行',
    request_id    VARCHAR(64)  NULL COMMENT '入组使用的 requestId',
    CONSTRAINT uk_batch_member_batch_page UNIQUE (batch_id, page_id),
    CONSTRAINT fk_batch_member_batch FOREIGN KEY (batch_id) REFERENCES deacidification_batch (id),
    CONSTRAINT fk_batch_member_page FOREIGN KEY (page_id) REFERENCES archive_page (id)
);

-- 处理占用表：主键即 page_id，数据库层保证同一页同一时刻只能被一个进行中批次占用。
CREATE TABLE IF NOT EXISTS page_occupation (
    page_id    BIGINT NOT NULL PRIMARY KEY COMMENT '被占用的档案页ID',
    batch_id   BIGINT NOT NULL COMMENT '当前占用该页的脱酸批次ID',
    held_since DATETIME(6) NOT NULL COMMENT '占用开始时间，时区 Asia/Shanghai',
    CONSTRAINT fk_page_occupation_batch FOREIGN KEY (batch_id) REFERENCES deacidification_batch (id)
);

CREATE TABLE IF NOT EXISTS treatment_round (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id    BIGINT       NOT NULL COMMENT '处理所在批次ID',
    page_id     BIGINT       NOT NULL COMMENT '被处理的档案页ID',
    round_no    INT          NOT NULL COMMENT '该页在该批次内的处理轮次，从1递增；只追加，不覆盖',
    ph_before   DECIMAL(4,2) NULL COMMENT '处理前pH，无量纲，允许区间6.50-8.50；NULL 表示读数缺失',
    ph_after    DECIMAL(4,2) NULL COMMENT '处理后pH，无量纲，允许区间6.50-8.50且不得低于处理前；NULL 表示读数缺失',
    reagent_lot VARCHAR(64)  NOT NULL COMMENT '药剂批号，即药剂来源',
    created_at  DATETIME(6)  NOT NULL COMMENT '读数登记时间，时区 Asia/Shanghai',
    CONSTRAINT uk_treatment_round_no UNIQUE (batch_id, page_id, round_no),
    CONSTRAINT fk_treatment_round_batch FOREIGN KEY (batch_id) REFERENCES deacidification_batch (id),
    CONSTRAINT fk_treatment_round_page FOREIGN KEY (page_id) REFERENCES archive_page (id)
);

CREATE TABLE IF NOT EXISTS batch_rejection (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id   BIGINT       NOT NULL COMMENT '质检退回所属批次ID',
    reason     VARCHAR(500) NOT NULL COMMENT '质检退回理由；只追加，旧理由不被覆盖',
    created_at DATETIME(6)  NOT NULL COMMENT '退回时间，时区 Asia/Shanghai',
    CONSTRAINT fk_batch_rejection_batch FOREIGN KEY (batch_id) REFERENCES deacidification_batch (id)
);

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id  VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '入组/封存幂等键 requestId',
    operation   VARCHAR(16)  NOT NULL COMMENT '操作类型：ENROLL 入组；SEAL 封存',
    fingerprint VARCHAR(200) NOT NULL COMMENT '首次请求参数指纹；同 requestId 异参返回409',
    result_json CLOB         NOT NULL COMMENT '首次处理结果快照（JSON），同参重放原样返回',
    batch_id    BIGINT       NULL COMMENT '关联批次ID，便于排查',
    created_at  DATETIME(6)  NOT NULL COMMENT '首次请求时间，时区 Asia/Shanghai'
);
