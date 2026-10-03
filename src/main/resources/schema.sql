-- 档案脱酸业务链建表脚本（H2 MODE=MySQL），时间均为 Asia/Shanghai 本地时间，由应用写入。
-- pH 无量纲，保留两位小数；允许范围 6.5 至 8.5 在封存校验时判定。

CREATE TABLE IF NOT EXISTS archive_page (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '档案页主键',
    collection_no VARCHAR(64) NOT NULL COMMENT '馆藏号，全库唯一',
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE' COMMENT '页状态：AVAILABLE=可入组，IN_PROCESS=处理中',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
    CONSTRAINT uk_archive_page_collection_no UNIQUE (collection_no)
) COMMENT='档案页登记表';

CREATE TABLE IF NOT EXISTS deacid_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '脱酸批次主键',
    batch_no VARCHAR(64) NOT NULL COMMENT '批次编号，全库唯一',
    status VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS' COMMENT '批次状态：IN_PROGRESS=进行中，SEALED=已封存（只读）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '批次创建时间',
    sealed_at TIMESTAMP NULL COMMENT '封存时间，未封存为 NULL',
    CONSTRAINT uk_deacid_batch_batch_no UNIQUE (batch_no)
) COMMENT='脱酸批次表';

CREATE TABLE IF NOT EXISTS batch_member (
    batch_id BIGINT NOT NULL COMMENT '所属批次主键',
    page_id BIGINT NOT NULL COMMENT '档案页主键',
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入组时间',
    CONSTRAINT pk_batch_member PRIMARY KEY (batch_id, page_id)
) COMMENT='批次与档案页的成员关系，封存后保留历史';

CREATE TABLE IF NOT EXISTS page_occupancy (
    page_id BIGINT NOT NULL PRIMARY KEY COMMENT '档案页主键，唯一约束保证同一页同一时刻仅处于一个进行中批次',
    batch_id BIGINT NOT NULL COMMENT '占用该页的进行中批次主键',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用建立时间'
) COMMENT='处理占用表，批次封存或档案页归还时删除';

CREATE TABLE IF NOT EXISTS treatment_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '处理记录主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次主键',
    page_id BIGINT NOT NULL COMMENT '档案页主键',
    round_no INT NOT NULL COMMENT '处理轮次，同一页在同一批次内从 1 递增，旧轮次只读',
    ph_before DECIMAL(4,2) NOT NULL COMMENT '处理前 pH 测量值，无量纲',
    ph_after DECIMAL(4,2) NOT NULL COMMENT '处理后 pH 测量值，无量纲，封存时要求 6.5 至 8.5 且不低于处理前',
    reagent_batch_no VARCHAR(64) NOT NULL COMMENT '药剂批号，标识药剂来源',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    CONSTRAINT uk_treatment_round UNIQUE (batch_id, page_id, round_no)
) COMMENT='脱酸处理读数记录，追加式，不允许改写历史轮次';

CREATE TABLE IF NOT EXISTS qc_rejection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '退回记录主键',
    batch_id BIGINT NOT NULL COMMENT '被退回批次主键',
    attempt_no INT NOT NULL COMMENT '封存尝试序号，从 1 递增',
    reason VARCHAR(1024) NOT NULL COMMENT '退回理由，列出问题页及原因，写入后不得覆盖',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '退回时间'
) COMMENT='质检退回记录，追加式保留全部历史';

CREATE TABLE IF NOT EXISTS enroll_request (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '入组幂等键，同参重放返回原状态，异参返回 409',
    batch_id BIGINT NOT NULL COMMENT '入组目标批次主键',
    page_id BIGINT NOT NULL COMMENT '入组档案页主键',
    outcome VARCHAR(16) NOT NULL COMMENT '入组结果：ENROLLED=成功',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次处理时间'
) COMMENT='入组请求幂等记录表';

CREATE TABLE IF NOT EXISTS seal_request (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '封存幂等键，同参重放返回原状态，异参返回 409',
    batch_id BIGINT NOT NULL COMMENT '封存目标批次主键',
    outcome VARCHAR(16) NOT NULL COMMENT '封存结果：SEALED=成功，REJECTED=质检退回',
    detail VARCHAR(1024) NOT NULL COMMENT '结果明细，退回时含问题页列表',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次处理时间'
) COMMENT='封存请求幂等记录表';