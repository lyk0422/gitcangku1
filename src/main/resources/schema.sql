-- 珊瑚移植样方：记录样方状态与封存时刻，时间均为 UTC
CREATE TABLE IF NOT EXISTS quadrat (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '样方主键',
    quadrat_code VARCHAR(64) NOT NULL COMMENT '样方编号，项目内唯一',
    name VARCHAR(128) NULL COMMENT '样方名称，可空',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT '样方状态：OPEN开放/SEALED已封存',
    created_at TIMESTAMP NOT NULL COMMENT '创建时刻（UTC）',
    sealed_at TIMESTAMP NULL COMMENT '封存时刻（UTC），未封存为NULL',
    CONSTRAINT uk_quadrat_code UNIQUE (quadrat_code)
);
COMMENT ON TABLE quadrat IS '珊瑚移植样方，最多容纳12株活跃移植体';

-- 移植登记：固化来源苗圃、样方与UTC时刻；植株死亡后记录保留，仅状态变化
CREATE TABLE IF NOT EXISTS transplant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '移植记录主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求编号，同参重放返回原结果，异参冲突',
    plant_code VARCHAR(64) NOT NULL COMMENT '植株编号，项目内唯一，死亡后仍保留',
    nursery VARCHAR(128) NOT NULL COMMENT '来源苗圃',
    quadrat_id BIGINT NOT NULL COMMENT '所属样方ID，登记后不可变更',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '当前状态：PENDING待复核/ALIVE存活/DEAD死亡',
    transplanted_at TIMESTAMP NOT NULL COMMENT '移植登记时刻（UTC）',
    CONSTRAINT uk_transplant_request UNIQUE (request_id),
    CONSTRAINT uk_transplant_plant UNIQUE (plant_code),
    CONSTRAINT fk_transplant_quadrat FOREIGN KEY (quadrat_id) REFERENCES quadrat (id)
);
COMMENT ON TABLE transplant IS '珊瑚移植登记，死亡后记录不可删除';

-- 复核记录：每次复核追加一条，构成复核链
CREATE TABLE IF NOT EXISTS review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '复核记录主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求编号，同参重放返回原结果，异参冲突',
    transplant_id BIGINT NOT NULL COMMENT '被复核的移植记录ID',
    result VARCHAR(16) NOT NULL COMMENT '复核结果：ALIVE存活/DEAD死亡/PENDING待复核',
    note VARCHAR(255) NULL COMMENT '复核备注，可空',
    reviewed_at TIMESTAMP NOT NULL COMMENT '复核时刻（UTC）',
    CONSTRAINT uk_review_request UNIQUE (request_id),
    CONSTRAINT fk_review_transplant FOREIGN KEY (transplant_id) REFERENCES transplant (id)
);
COMMENT ON TABLE review IS '复核记录，按时间构成植株复核链';

-- 封存快照：每个样方仅一个清单版本，固化各状态数量与植株清单（含死亡）
CREATE TABLE IF NOT EXISTS seal_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '封存快照主键',
    quadrat_id BIGINT NOT NULL COMMENT '被封存样方ID，唯一约束保证一个清单版本',
    alive_count INT NOT NULL COMMENT '封存时存活数量',
    dead_count INT NOT NULL COMMENT '封存时死亡数量（记录保留）',
    pending_count INT NOT NULL COMMENT '封存时待复核数量，封存前必须为0',
    plant_codes VARCHAR(2048) NOT NULL COMMENT '封存时植株编号清单（含死亡），逗号分隔',
    sealed_at TIMESTAMP NOT NULL COMMENT '封存时刻（UTC）',
    CONSTRAINT uk_seal_quadrat UNIQUE (quadrat_id),
    CONSTRAINT fk_seal_quadrat FOREIGN KEY (quadrat_id) REFERENCES quadrat (id)
);
COMMENT ON TABLE seal_snapshot IS '样方封存快照，固化状态数量与植株清单';