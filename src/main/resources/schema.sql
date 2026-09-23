-- 藏品归还裁决领域建表脚本；同时兼容 H2(MODE=MySQL) 与 MySQL。
-- 所有金额/时间以外的业务编号均为不透明字符串；时间统一由应用按 Asia/Shanghai 解释。

create table if not exists restitution_case (
    id           bigint       not null auto_increment primary key,
    case_key     varchar(64)  not null comment '案件对外编号，全局唯一',
    status       varchar(16)  not null comment '案件状态：OPEN=审理中，DECIDED=已裁决终态',
    version      bigint       not null default 1 comment '案件乐观版本号，初始1；登记/撤回主张、证据追加/撤销、首次批准、成功裁决均加一',
    decided_at   datetime     null comment '裁决落定时刻（Asia/Shanghai），未裁决为空',
    created_at   datetime     not null default current_timestamp comment '案件创建时刻（Asia/Shanghai）',
    constraint uk_case_key unique (case_key)
) comment='藏品归还案件主表';

create table if not exists case_artifact (
    id           bigint       not null auto_increment primary key,
    case_id      bigint       not null comment '所属案件主键',
    artifact_no  varchar(128) not null comment '藏品编号，案内唯一、非空',
    ordinal      int          not null comment '登记顺序，用于稳定排序与回放',
    constraint uk_case_artifact unique (case_id, artifact_no)
) comment='案件藏品清单，每案1至10个唯一藏品编号';

create table if not exists claim (
    id                bigint        not null auto_increment primary key,
    case_id           bigint        not null comment '所属案件主键',
    claim_key         varchar(64)   not null comment '主张业务编号，案内唯一',
    applicant         varchar(128)  not null comment '申请人标识，取自请求内容',
    statement         varchar(4000) not null comment '非空主张说明原文',
    status            varchar(16)   not null comment '主张状态：REGISTERED=有效，WITHDRAWN=已撤回（不可恢复、不可改）',
    evidence_version  bigint        not null default 0 comment '证据集版本，初始0；追加或撤销证据加一，批准仅对该版本有效',
    created_at        datetime      not null default current_timestamp comment '主张登记时刻（Asia/Shanghai）',
    withdrawn_at      datetime      null comment '主张撤回时刻（Asia/Shanghai），未撤回为空',
    constraint uk_claim_case_key unique (case_id, claim_key)
) comment='归还主张表，主张之间藏品范围可重叠';

create table if not exists claim_artifact (
    id           bigint       not null auto_increment primary key,
    claim_id     bigint       not null comment '所属主张主键',
    case_id      bigint       not null comment '所属案件主键，便于集合校验',
    artifact_no  varchar(128) not null comment '主张覆盖的藏品编号，主张内唯一',
    ordinal      int          not null comment '登记顺序，用于稳定排序与回放',
    constraint uk_claim_artifact unique (claim_id, artifact_no)
) comment='主张覆盖藏品子集，可跨主张重叠';

create table if not exists evidence (
    id            bigint        not null auto_increment primary key,
    case_id       bigint        not null comment '所属案件主键，evidence_key 案内唯一',
    claim_id      bigint        not null comment '所属主张主键，每主张累计至多20份（含已撤销）',
    evidence_key  varchar(64)   not null comment '证据业务编号，案内唯一',
    summary       varchar(4000) not null comment '证据非空摘要，证据只存摘要不存原件',
    status        varchar(16)   not null comment '证据状态：ACTIVE=有效，REVOKED=已撤销（历史保留）',
    created_at    datetime      not null default current_timestamp comment '证据追加时刻（Asia/Shanghai）',
    revoked_at    datetime      null comment '证据撤销时刻（Asia/Shanghai），未撤销为空',
    constraint uk_evidence_case_key unique (case_id, evidence_key)
) comment='证据表，仅存非空摘要，撤销后历史保留';

create table if not exists approval (
    id               bigint       not null auto_increment primary key,
    case_id          bigint       not null comment '所属案件主键',
    claim_id         bigint       not null comment '被批准主张主键',
    reviewer         varchar(128) not null comment '评审人标识，不得等于该主张申请人',
    evidence_version bigint       not null comment '批准针对的证据版本；证据变更后旧批准失效，需重新批准',
    created_at       datetime     not null default current_timestamp comment '批准时刻（Asia/Shanghai）',
    constraint uk_approval unique (claim_id, reviewer, evidence_version)
) comment='评审批准记录，同一评审人对同一主张同一证据版本不重复计数';

create table if not exists frozen_decision (
    case_id     bigint      not null primary key comment '案件主键，一案至多一份裁决',
    version     bigint      not null comment '裁决落定后的案件版本号（落定时在原版本上加一）',
    request_id  varchar(80) not null comment '触发裁决的全局 requestId',
    decided_at  datetime    not null default current_timestamp comment '裁决时刻（Asia/Shanghai）'
) comment='裁决冻结主表，终态后历史查询直接读快照不重算';

create table if not exists frozen_claim (
    id          bigint       not null auto_increment primary key,
    case_id     bigint       not null comment '所属案件主键',
    claim_id    bigint       not null comment '被选定主张主键',
    claim_key   varchar(64)  not null comment '被选定主张编号',
    applicant   varchar(128) not null comment '申请人标识，藏品冻结到该主体',
    statement   varchar(4000) not null comment '主张说明快照',
    ordinal     int          not null comment '裁决提交列表中的顺序',
    constraint uk_frozen_claim unique (case_id, claim_id)
) comment='裁决选定主张快照';

create table if not exists frozen_artifact (
    id           bigint       not null auto_increment primary key,
    case_id      bigint       not null comment '所属案件主键',
    artifact_no  varchar(128) not null comment '裁决冻结的藏品编号，案内不重不漏',
    claim_id     bigint       not null comment '该藏品判给的主张主键',
    applicant    varchar(128) not null comment '该藏品冻结到的申请人',
    ordinal      int          not null comment '冻结输出顺序',
    constraint uk_frozen_artifact unique (case_id, artifact_no)
) comment='裁决冻结的藏品到申请人对应关系快照';

create table if not exists frozen_evidence (
    id               bigint        not null auto_increment primary key,
    case_id          bigint        not null comment '所属案件主键',
    claim_id         bigint        not null comment '所属主张主键',
    evidence_id      bigint        not null comment '原证据主键',
    evidence_key     varchar(64)   not null comment '证据编号快照',
    summary          varchar(4000) not null comment '证据非空摘要快照',
    evidence_version bigint        not null comment '裁决时该主张的当前证据版本',
    ordinal          int           not null comment '冻结输出顺序',
    constraint uk_frozen_evidence unique (case_id, evidence_id)
) comment='裁决时各选定主张的有效证据快照';

create table if not exists frozen_approval (
    id               bigint       not null auto_increment primary key,
    case_id          bigint       not null comment '所属案件主键',
    claim_id         bigint       not null comment '所属主张主键',
    reviewer         varchar(128) not null comment '评审人快照',
    evidence_version bigint       not null comment '批准对应的证据版本快照',
    ordinal          int          not null comment '冻结输出顺序',
    constraint uk_frozen_approval unique (case_id, claim_id, reviewer, evidence_version)
) comment='裁决时满足当前版本要求的批准信息快照';

create table if not exists request_record (
    request_id     varchar(80)  not null primary key comment '全局写操作幂等键 requestId',
    actor_id       varchar(128) not null comment '首次操作者标识 X-Actor-Id，异操作者不允许重放',
    op_key         varchar(64)  not null comment '操作类型，如 register_claim/append_evidence/decide',
    fingerprint    varchar(8000) not null comment '规范化参数指纹（集合排序后序列化），集合换序指纹相同',
    http_status    int          not null comment '首次成功响应 HTTP 状态码，用于重放',
    response_body  text         null comment '首次成功响应 JSON 原文，用于原样重放',
    created_at     datetime     not null default current_timestamp comment '首次成功落库时刻（Asia/Shanghai）'
) comment='写操作幂等记录，仅成功结果占键，失败不占键';
