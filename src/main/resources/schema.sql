-- 电芯放电业务建表脚本（H2 MODE=MySQL，应用启动自动执行）
-- 时间字段统一为 UTC 时刻；电压单位：伏特(V)

-- 放电批次：一组待交接电芯的容器，一次最多 50 枚
CREATE TABLE IF NOT EXISTS discharge_batch (
    batch_code   VARCHAR(64)  NOT NULL COMMENT '批次编号，合成编号，全局唯一',
    status       VARCHAR(16)  NOT NULL COMMENT '批次状态：OPEN=进行中，HANDED_OVER=已交接',
    created_at   TIMESTAMP    NOT NULL COMMENT '批次创建时刻（UTC）',
    PRIMARY KEY (batch_code)
);

-- 电芯：回收站接收的单枚电芯，入批后归属唯一进行中批次
CREATE TABLE IF NOT EXISTS battery_cell (
    cell_code    VARCHAR(64)  NOT NULL COMMENT '电芯编号，合成编号，全局唯一',
    status       VARCHAR(16)  NOT NULL COMMENT '电芯状态：AVAILABLE=可入批，IN_BATCH=在批，ISOLATED=已隔离，HANDED_OVER=已交接',
    batch_code   VARCHAR(64)  NULL COMMENT '当前所属进行中批次编号；为空表示不在任何批次',
    created_at   TIMESTAMP    NOT NULL COMMENT '电芯登记时刻（UTC）',
    PRIMARY KEY (cell_code)
);

-- 放电读数：入批初始电压与终测电压的不可变记录，作废仅置标志不删除
CREATE TABLE IF NOT EXISTS discharge_reading (
    reading_code VARCHAR(64)  NOT NULL COMMENT '读数编号，合成编号，全局唯一',
    cell_code    VARCHAR(64)  NOT NULL COMMENT '被测电芯编号',
    batch_code   VARCHAR(64)  NOT NULL COMMENT '测量时所属批次编号',
    reading_type VARCHAR(8)   NOT NULL COMMENT '读数类型：INTAKE=入批初始电压，FINAL=终测电压',
    voltage      DECIMAL(5,3) NOT NULL COMMENT '电压读数，单位伏特(V)',
    measured_at  TIMESTAMP    NOT NULL COMMENT '测量时刻（UTC），终测须晚于入批时刻',
    valid        BOOLEAN      NOT NULL COMMENT '是否有效：TRUE=有效，FALSE=已作废（历史保留）',
    request_id   VARCHAR(64)  NOT NULL COMMENT '产生该读数的幂等请求编号',
    created_at   TIMESTAMP    NOT NULL COMMENT '记录写入时刻（UTC）',
    PRIMARY KEY (reading_code)
);
CREATE INDEX IF NOT EXISTS idx_reading_cell ON discharge_reading (cell_code, batch_code);

-- 电芯隔离记录：异常电芯退出批次的原因留痕，不删除其读数历史
CREATE TABLE IF NOT EXISTS cell_isolation (
    id           BIGINT AUTO_INCREMENT NOT NULL COMMENT '自增主键',
    cell_code    VARCHAR(64)  NOT NULL COMMENT '被隔离电芯编号',
    batch_code   VARCHAR(64)  NOT NULL COMMENT '隔离时所属批次编号',
    reason       VARCHAR(255) NOT NULL COMMENT '隔离原因（如终测电压超限）',
    isolated_at  TIMESTAMP    NOT NULL COMMENT '隔离时刻（UTC）',
    PRIMARY KEY (id)
);

-- 交接单：批次交接签收快照，冻结交接双方与 UTC 时刻
CREATE TABLE IF NOT EXISTS handover (
    handover_code VARCHAR(64)  NOT NULL COMMENT '交接单编号，合成编号，全局唯一',
    batch_code    VARCHAR(64)  NOT NULL COMMENT '被交接批次编号，一批仅允许一次交接',
    from_party    VARCHAR(64)  NOT NULL COMMENT '交出方（回收站）',
    to_party      VARCHAR(64)  NOT NULL COMMENT '接收方（拆解区）',
    handed_over_at TIMESTAMP   NOT NULL COMMENT '交接时刻（UTC）',
    request_id    VARCHAR(64)  NOT NULL COMMENT '产生该交接的幂等请求编号',
    PRIMARY KEY (handover_code),
    UNIQUE (batch_code)
);

-- 交接电芯明细：交接时冻结的电芯清单与各自终测读数编号
CREATE TABLE IF NOT EXISTS handover_cell (
    handover_code      VARCHAR(64) NOT NULL COMMENT '所属交接单编号',
    cell_code          VARCHAR(64) NOT NULL COMMENT '被交接电芯编号',
    final_reading_code VARCHAR(64) NOT NULL COMMENT '交接时采用的最新有效终测读数编号',
    PRIMARY KEY (handover_code, cell_code)
);

-- 幂等请求键：测量及交接请求的 requestId，同参重放返回原响应，异参冲突
CREATE TABLE IF NOT EXISTS idempotency_key (
    request_id      VARCHAR(64)  NOT NULL COMMENT '请求编号，调用方提供，全局唯一',
    endpoint        VARCHAR(64)  NOT NULL COMMENT '请求入口标识',
    params_hash     VARCHAR(64)  NOT NULL COMMENT '请求参数摘要，用于区分同键异参',
    response_status INT          NOT NULL COMMENT '首次请求的响应状态码',
    response_body   CLOB         NOT NULL COMMENT '首次请求的响应报文，重放时原样返回',
    created_at      TIMESTAMP    NOT NULL COMMENT '键创建时刻（UTC）',
    PRIMARY KEY (request_id)
);

-- 合成电芯数据：本地入口与演示调用使用
INSERT INTO battery_cell (cell_code, status, batch_code, created_at)
SELECT * FROM (
    SELECT 'CELL-001', 'AVAILABLE', CAST(NULL AS VARCHAR), TIMESTAMP '2026-01-01 00:00:00'
    UNION ALL SELECT 'CELL-002', 'AVAILABLE', CAST(NULL AS VARCHAR), TIMESTAMP '2026-01-01 00:00:00'
    UNION ALL SELECT 'CELL-003', 'AVAILABLE', CAST(NULL AS VARCHAR), TIMESTAMP '2026-01-01 00:00:00'
) seed
WHERE NOT EXISTS (SELECT 1 FROM battery_cell);