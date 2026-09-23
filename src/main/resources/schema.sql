-- 藏品归还裁决领域建表脚本（H2 MODE=MySQL 下执行；内联 COMMENT 语法 MySQL 8 同样兼容）

CREATE TABLE IF NOT EXISTS restitution_case (
    id          VARCHAR(64)  NOT NULL COMMENT '案件编号（UUID 去横线）',
    status      VARCHAR(16)  NOT NULL COMMENT '案件状态：OPEN 可写入，DECIDED 已终态裁决冻结',
    version     BIGINT       NOT NULL COMMENT '案件乐观版本，登记/撤回主张、证据增撤、首次批准、裁决均加一',
    created_at  BIGINT       NOT NULL COMMENT '创建时间（epoch 毫秒，业务时区 Asia/Shanghai）',
    decided_at  BIGINT       NULL COMMENT '裁决时间（epoch 毫秒），未裁决为空',
    CONSTRAINT pk_restitution_case PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS case_item (
    case_id     VARCHAR(64)  NOT NULL COMMENT '案件编号',
    item_no     VARCHAR(64)  NOT NULL COMMENT '藏品编号，案内唯一，全案 1 至 10 个',
    ord         INT          NOT NULL COMMENT '登记顺序，从 0 开始',
    CONSTRAINT pk_case_item PRIMARY KEY (case_id, item_no)
);

CREATE TABLE IF NOT EXISTS claim (
    id            BIGINT AUTO_INCREMENT NOT NULL COMMENT '主张自增主键',
    case_id       VARCHAR(64)  NOT NULL COMMENT '案件编号',
    claim_key     VARCHAR(64)  NOT NULL COMMENT '案内唯一主张键',
    applicant     VARCHAR(128) NOT NULL COMMENT '申请人标识',
    statement     VARCHAR(2000) NOT NULL COMMENT '主张说明，登记后非空且不可改',
    withdrawn     BOOLEAN      NOT NULL COMMENT '是否已撤回：1 已撤回且不可恢复，内容保留',
    version       BIGINT       NOT NULL COMMENT '主张当前证据版本，初始 0，证据追加/撤销加一',
    created_at    BIGINT       NOT NULL COMMENT '登记时间（epoch 毫秒）',
    withdrawn_at  BIGINT       NULL COMMENT '撤回时间（epoch 毫秒），未撤回为空',
    CONSTRAINT pk_claim PRIMARY KEY (id),
    CONSTRAINT uk_claim_case_key UNIQUE (case_id, claim_key)
);

CREATE TABLE IF NOT EXISTS claim_item (
    claim_id    BIGINT       NOT NULL COMMENT '主张主键',
    item_no     VARCHAR(64)  NOT NULL COMMENT '藏品编号，为主张登记时案件藏品的子集',
    ord         INT          NOT NULL COMMENT '登记顺序，从 0 开始',
    CONSTRAINT pk_claim_item PRIMARY KEY (claim_id, item_no)
);

CREATE TABLE IF NOT EXISTS evidence (
    id            BIGINT AUTO_INCREMENT NOT NULL COMMENT '证据自增主键',
    case_id       VARCHAR(64)  NOT NULL COMMENT '案件编号',
    claim_id      BIGINT       NOT NULL COMMENT '所属主张主键',
    evidence_key  VARCHAR(64)  NOT NULL COMMENT '案内唯一证据键',
    summary       VARCHAR(2000) NOT NULL COMMENT '证据非空摘要，只存摘要',
    version       BIGINT       NOT NULL COMMENT '该证据创建时主张所处的证据版本',
    active        BOOLEAN      NOT NULL COMMENT '1 有效；0 已撤销，行保留为历史',
    created_at    BIGINT       NOT NULL COMMENT '创建时间（epoch 毫秒）',
    revoked_at    BIGINT       NULL COMMENT '撤销时间（epoch 毫秒），未撤销为空',
    CONSTRAINT pk_evidence PRIMARY KEY (id),
    CONSTRAINT uk_evidence_case_key UNIQUE (case_id, evidence_key)
);

CREATE TABLE IF NOT EXISTS approval (
    id               BIGINT AUTO_INCREMENT NOT NULL COMMENT '批准自增主键',
    case_id          VARCHAR(64)  NOT NULL COMMENT '案件编号',
    claim_id         BIGINT       NOT NULL COMMENT '主张主键',
    evidence_version BIGINT       NOT NULL COMMENT '批准针对的主张证据版本',
    reviewer         VARCHAR(128) NOT NULL COMMENT '评审人标识，不得等于主张申请人',
    created_at       BIGINT       NOT NULL COMMENT '批准时间（epoch 毫秒）',
    CONSTRAINT pk_approval PRIMARY KEY (id),
    CONSTRAINT uk_approval_claim_version_reviewer UNIQUE (claim_id, evidence_version, reviewer)
);

CREATE TABLE IF NOT EXISTS frozen_item (
    case_id    VARCHAR(64)  NOT NULL COMMENT '案件编号',
    item_no    VARCHAR(64)  NOT NULL COMMENT '藏品编号',
    claim_key  VARCHAR(64)  NOT NULL COMMENT '裁决中选主张键',
    applicant  VARCHAR(128) NOT NULL COMMENT '中选主张申请人，即藏品归还对象',
    ord        INT          NOT NULL COMMENT '全案藏品顺序，从 0 开始',
    CONSTRAINT pk_frozen_item PRIMARY KEY (case_id, item_no)
);

CREATE TABLE IF NOT EXISTS frozen_claim (
    case_id    VARCHAR(64)  NOT NULL COMMENT '案件编号',
    claim_key  VARCHAR(64)  NOT NULL COMMENT '中选主张键',
    applicant  VARCHAR(128) NOT NULL COMMENT '申请人快照',
    statement  VARCHAR(2000) NOT NULL COMMENT '主张说明快照，不可改内容的冻结副本',
    CONSTRAINT pk_frozen_claim PRIMARY KEY (case_id, claim_key)
);

CREATE TABLE IF NOT EXISTS frozen_evidence (
    case_id       VARCHAR(64)  NOT NULL COMMENT '案件编号',
    claim_key     VARCHAR(64)  NOT NULL COMMENT '所属中选主张键',
    evidence_key  VARCHAR(64)  NOT NULL COMMENT '证据键快照',
    summary       VARCHAR(2000) NOT NULL COMMENT '证据摘要快照',
    version       BIGINT       NOT NULL COMMENT '证据创建时主张所处的证据版本',
    CONSTRAINT pk_frozen_evidence PRIMARY KEY (case_id, evidence_key)
);

CREATE TABLE IF NOT EXISTS frozen_approval (
    case_id          VARCHAR(64)  NOT NULL COMMENT '案件编号',
    claim_key        VARCHAR(64)  NOT NULL COMMENT '所属中选主张键',
    evidence_version BIGINT       NOT NULL COMMENT '支撑裁决的当前证据版本',
    reviewer         VARCHAR(128) NOT NULL COMMENT '评审人快照，每主张两名不同评审人',
    created_at       BIGINT       NOT NULL COMMENT '原批准时间（epoch 毫秒）',
    CONSTRAINT pk_frozen_approval PRIMARY KEY (case_id, claim_key, evidence_version, reviewer)
);

CREATE TABLE IF NOT EXISTS request_record (
    request_id    VARCHAR(64)  NOT NULL COMMENT '全局写操作幂等键 X-Request-Id',
    actor         VARCHAR(128) NOT NULL COMMENT '操作者 X-Actor-Id，幂等键按操作者隔离',
    request_hash  VARCHAR(64)  NOT NULL COMMENT '操作标识+规范请求参数的 SHA-256 指纹',
    status_code   INT          NOT NULL COMMENT '首次执行返回的 HTTP 状态码',
    response_body TEXT         NOT NULL COMMENT '首次执行响应体 JSON，重放原样返回',
    created_at    BIGINT       NOT NULL COMMENT '首次执行时间（epoch 毫秒）',
    CONSTRAINT pk_request_record PRIMARY KEY (request_id, actor)
);
