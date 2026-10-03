-- 珊瑚移植样方与复核持久化结构（H2 MODE=MySQL 与 MySQL 均可执行）
CREATE TABLE IF NOT EXISTS plot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '样方主键',
    plot_code VARCHAR(64) NOT NULL COMMENT '样方编号，项目内唯一',
    capacity INT NOT NULL DEFAULT 12 COMMENT '活跃移植体容量上限，单位：株；活跃=存活或待复核',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '样方状态：ACTIVE=可登记与复核，SEALED=已封存只读',
    sealed_at TIMESTAMP(6) NULL COMMENT '封存完成UTC时刻；NULL表示未封存',
    CONSTRAINT uk_plot_code UNIQUE (plot_code)
);
COMMENT ON TABLE plot IS '珊瑚移植样方，记录容量与封存状态';

CREATE TABLE IF NOT EXISTS transplant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '移植记录主键',
    request_id VARCHAR(64) NOT NULL COMMENT '移植登记幂等请求编号，项目内唯一',
    plant_code VARCHAR(64) NOT NULL COMMENT '植株编号，项目内唯一；死亡后记录保留不可删',
    nursery VARCHAR(128) NOT NULL COMMENT '来源苗圃名称，登记时固化',
    plot_id BIGINT NOT NULL COMMENT '所属样方主键，登记后不得转到其他活跃样方',
    transplanted_at TIMESTAMP(6) NOT NULL COMMENT '移植登记UTC时刻',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '当前状态：ALIVE=存活，DEAD=死亡（终态，释放容量），PENDING=待复核',
    CONSTRAINT uk_transplant_request UNIQUE (request_id),
    CONSTRAINT uk_transplant_plant UNIQUE (plant_code),
    CONSTRAINT fk_transplant_plot FOREIGN KEY (plot_id) REFERENCES plot (id)
);
COMMENT ON TABLE transplant IS '珊瑚移植登记记录，死亡后保留历史';

CREATE TABLE IF NOT EXISTS review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '复核记录主键',
    request_id VARCHAR(64) NOT NULL COMMENT '复核幂等请求编号，项目内唯一',
    transplant_id BIGINT NOT NULL COMMENT '被复核的移植记录主键',
    result VARCHAR(16) NOT NULL COMMENT '复核结论：ALIVE=存活，DEAD=死亡，PENDING=待复核',
    reviewed_at TIMESTAMP(6) NOT NULL COMMENT '复核登记UTC时刻',
    CONSTRAINT uk_review_request UNIQUE (request_id),
    CONSTRAINT fk_review_transplant FOREIGN KEY (transplant_id) REFERENCES transplant (id)
);
COMMENT ON TABLE review IS '复核记录，按时间构成单株复核链';

CREATE TABLE IF NOT EXISTS plot_seal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '封存快照主键',
    request_id VARCHAR(64) NOT NULL COMMENT '封存幂等请求编号，项目内唯一',
    plot_id BIGINT NOT NULL COMMENT '被封存样方主键，一个样方仅一份快照',
    sealed_at TIMESTAMP(6) NOT NULL COMMENT '封存完成UTC时刻',
    alive_count INT NOT NULL COMMENT '封存时存活植株数，单位：株',
    dead_count INT NOT NULL COMMENT '封存时死亡植株数，单位：株；死亡记录保留在快照中',
    pending_count INT NOT NULL COMMENT '封存时待复核植株数，单位：株；封存前置校验保证为0',
    plant_list CLOB NOT NULL COMMENT '封存时植株清单JSON，元素含plantCode与status',
    CONSTRAINT uk_seal_request UNIQUE (request_id),
    CONSTRAINT uk_seal_plot UNIQUE (plot_id),
    CONSTRAINT fk_seal_plot FOREIGN KEY (plot_id) REFERENCES plot (id)
);
COMMENT ON TABLE plot_seal IS '样方封存快照，固化各状态数量与植株清单';