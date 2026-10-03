-- 档案脱酸业务链建表脚本（H2 MODE=MySQL / MySQL 8 通用语法子集）。
-- 时间字段均为应用服务器本地时间（Asia/Shanghai），由数据库默认 CURRENT_TIMESTAMP 或服务端写入。

CREATE TABLE IF NOT EXISTS archive_page (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    collection_no VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_archive_page_collection_no UNIQUE (collection_no)
);
COMMENT ON TABLE archive_page IS '档案页：一页待脱酸处理的档案，馆藏号全局唯一';
COMMENT ON COLUMN archive_page.id IS '档案页主键';
COMMENT ON COLUMN archive_page.collection_no IS '馆藏号，全局唯一，不可为空';
COMMENT ON COLUMN archive_page.created_at IS '登记时间，本地时区 Asia/Shanghai';

CREATE TABLE IF NOT EXISTS deacid_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sealed_at TIMESTAMP NULL,
    CONSTRAINT uk_deacid_batch_batch_no UNIQUE (batch_no)
);
COMMENT ON TABLE deacid_batch IS '脱酸批次：一个未封存批次可包含多页档案';
COMMENT ON COLUMN deacid_batch.batch_no IS '批次编号，全局唯一，不可为空';
COMMENT ON COLUMN deacid_batch.status IS '批次状态：OPEN=进行中可入组/追加读数，SEALED=已封存只读';
COMMENT ON COLUMN deacid_batch.sealed_at IS '封存时间，未封存为 NULL，本地时区 Asia/Shanghai';

CREATE TABLE IF NOT EXISTS batch_page (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    page_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    enrolled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_at TIMESTAMP NULL,
    CONSTRAINT uk_batch_page_request_id UNIQUE (request_id),
    CONSTRAINT fk_batch_page_batch FOREIGN KEY (batch_id) REFERENCES deacid_batch (id),
    CONSTRAINT fk_batch_page_page FOREIGN KEY (page_id) REFERENCES archive_page (id)
);
COMMENT ON TABLE batch_page IS '批次入组记录：一页档案在一个批次中的一次入组，request_id 幂等';
COMMENT ON COLUMN batch_page.request_id IS '入组幂等键，同键同参重放原状态，同键异参拒绝';
COMMENT ON COLUMN batch_page.status IS '入组状态：ACTIVE=进行中占用，RELEASED=已归还释放';
COMMENT ON COLUMN batch_page.released_at IS '归还时间，未归还为 NULL，本地时区 Asia/Shanghai';

CREATE TABLE IF NOT EXISTS page_occupancy (
    page_id BIGINT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    batch_page_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_occupancy_page FOREIGN KEY (page_id) REFERENCES archive_page (id)
);
COMMENT ON TABLE page_occupancy IS '处理占用：同一页同一时刻只允许处于一个进行中的批次，page_id 主键即唯一约束';
COMMENT ON COLUMN page_occupancy.page_id IS '被占用档案页主键，占用期间唯一，释放时整行删除';
COMMENT ON COLUMN page_occupancy.batch_page_id IS '对应入组记录主键';

CREATE TABLE IF NOT EXISTS treatment_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_page_id BIGINT NOT NULL,
    round_no INT NOT NULL,
    ph_before DECIMAL(4,2) NOT NULL,
    ph_after DECIMAL(4,2) NOT NULL,
    reagent_batch_no VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_treatment_round UNIQUE (batch_page_id, round_no),
    CONSTRAINT fk_treatment_batch_page FOREIGN KEY (batch_page_id) REFERENCES batch_page (id)
);
COMMENT ON TABLE treatment_record IS '处理轮次读数：只追加不修改，质检退回后追加新轮次';
COMMENT ON COLUMN treatment_record.round_no IS '轮次序号，同一入组记录内从 1 递增';
COMMENT ON COLUMN treatment_record.ph_before IS '处理前 pH 读数，无量纲，合规范围 6.5 至 8.5';
COMMENT ON COLUMN treatment_record.ph_after IS '处理后 pH 读数，无量纲，合规范围 6.5 至 8.5 且不得低于处理前';
COMMENT ON COLUMN treatment_record.reagent_batch_no IS '药剂批号（药剂来源），不可为空';

CREATE TABLE IF NOT EXISTS seal_request (
    request_id VARCHAR(64) PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    problems_json CLOB NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_seal_request_batch FOREIGN KEY (batch_id) REFERENCES deacid_batch (id)
);
COMMENT ON TABLE seal_request IS '封存请求留痕：幂等键与质检退回理由，只追加不覆盖';
COMMENT ON COLUMN seal_request.request_id IS '封存幂等键，同键重放原结果，同键异批次拒绝';
COMMENT ON COLUMN seal_request.outcome IS '封存结果：SEALED=封存成功，REJECTED=质检退回';
COMMENT ON COLUMN seal_request.problems_json IS '退回时的问题页清单 JSON，成功为 NULL';