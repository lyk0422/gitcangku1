-- 货运地磅争议结算 schema，H2 MODE=MySQL，重量单位统一为公斤，时间统一为 UTC。
DROP TABLE IF EXISTS errata_request;
DROP TABLE IF EXISTS settlement_freeze;
DROP TABLE IF EXISTS dispute;
DROP TABLE IF EXISTS reweigh_request;
DROP TABLE IF EXISTS weigh_ticket;
DROP TABLE IF EXISTS transport_order;

CREATE TABLE transport_order (
  order_no    VARCHAR(32)  NOT NULL COMMENT '运输单号，业务主键',
  vehicle_no  VARCHAR(32)  NOT NULL COMMENT '合成车辆号',
  cargo_desc  VARCHAR(128) NOT NULL COMMENT '货物描述',
  PRIMARY KEY (order_no)
) COMMENT='运输单';

CREATE TABLE weigh_ticket (
  id                   BIGINT AUTO_INCREMENT COMMENT '称量单ID',
  ticket_no            VARCHAR(40)  NOT NULL COMMENT '称量单编号，全局唯一',
  transport_order_no   VARCHAR(32)  NOT NULL COMMENT '关联运输单号',
  vehicle_no           VARCHAR(32)  NOT NULL COMMENT '合成车辆号',
  tare_kg              INT          NULL COMMENT '入场皮重，公斤；NULL表示尚未入场称量',
  gross_kg             INT          NULL COMMENT '出场毛重，公斤；NULL表示尚未出场称量',
  tare_time_utc        TIMESTAMP    NULL COMMENT '入场称量时间（UTC）',
  gross_time_utc       TIMESTAMP    NULL COMMENT '出场称量时间（UTC）',
  status               VARCHAR(16)  NOT NULL COMMENT 'OPEN待出场/GROSSED已出场/FROZEN净重已冻结',
  create_request_id    VARCHAR(64)  NOT NULL COMMENT '创建幂等请求ID',
  create_request_hash  VARCHAR(64)  NOT NULL COMMENT '创建请求参数哈希，异参重放判409',
  tare_request_id      VARCHAR(64)  NULL COMMENT '皮重登记幂等请求ID',
  tare_request_hash    VARCHAR(64)  NULL COMMENT '皮重登记参数哈希',
  gross_request_id     VARCHAR(64)  NULL COMMENT '毛重登记幂等请求ID',
  gross_request_hash   VARCHAR(64)  NULL COMMENT '毛重登记参数哈希',
  created_at_utc       TIMESTAMP    NOT NULL COMMENT '创建时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_ticket_no (ticket_no),
  UNIQUE KEY uk_ticket_create_req (create_request_id),
  CONSTRAINT fk_ticket_order FOREIGN KEY (transport_order_no) REFERENCES transport_order (order_no)
) COMMENT='称量单：一车一单，先皮重后毛重';

CREATE TABLE reweigh_request (
  id              BIGINT AUTO_INCREMENT COMMENT '复称申请ID',
  ticket_id       BIGINT       NOT NULL COMMENT '所属称量单ID',
  gross_kg        INT          NOT NULL COMMENT '复称毛重，公斤',
  reason          VARCHAR(256) NOT NULL COMMENT '复称理由留痕',
  status          VARCHAR(16)  NOT NULL COMMENT 'SUBMITTED已提交/ADOPTED已采纳/REJECTED已拒绝',
  request_id      VARCHAR(64)  NOT NULL COMMENT '幂等请求ID',
  request_hash    VARCHAR(64)  NOT NULL COMMENT '请求参数哈希',
  created_at_utc  TIMESTAMP    NOT NULL COMMENT '申请时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_reweigh_req (request_id),
  CONSTRAINT fk_reweigh_ticket FOREIGN KEY (ticket_id) REFERENCES weigh_ticket (id)
) COMMENT='复称申请：出场后30分钟内可追加新毛重记录';

CREATE TABLE dispute (
  id                   BIGINT AUTO_INCREMENT COMMENT '争议ID',
  ticket_id            BIGINT       NOT NULL COMMENT '所属称量单ID，每单仅一条争议',
  status               VARCHAR(16)  NOT NULL COMMENT 'OPEN待裁决/RESOLVED已裁决',
  adopted_source       VARCHAR(16)  NULL COMMENT '采纳来源：ORIGINAL原称量/REWEIGH复称；未裁决为NULL',
  adopted_reweigh_id   BIGINT       NULL COMMENT '采纳的复称申请ID；采纳原称量时为NULL',
  adjudication_reason  VARCHAR(256) NULL COMMENT '裁决理由留痕',
  adjudicator          VARCHAR(64)  NULL COMMENT '裁决员',
  adjudicated_at_utc   TIMESTAMP    NULL COMMENT '裁决时间（UTC）',
  request_id           VARCHAR(64)  NULL COMMENT '裁决幂等请求ID；未裁决为NULL',
  request_hash         VARCHAR(64)  NULL COMMENT '裁决参数哈希',
  created_at_utc       TIMESTAMP    NOT NULL COMMENT '争议创建时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_dispute_ticket (ticket_id),
  UNIQUE KEY uk_dispute_req (request_id),
  CONSTRAINT fk_dispute_ticket FOREIGN KEY (ticket_id) REFERENCES weigh_ticket (id)
) COMMENT='争议裁决：裁决员从原称量或复称中明确选一条';

CREATE TABLE settlement_freeze (
  id                  BIGINT AUTO_INCREMENT COMMENT '冻结ID',
  ticket_id           BIGINT       NOT NULL COMMENT '所属称量单ID，每单仅一份冻结快照',
  tare_kg             INT          NOT NULL COMMENT '冻结皮重，公斤',
  adopted_gross_kg    INT          NOT NULL COMMENT '采纳毛重，公斤',
  net_kg              INT          NOT NULL COMMENT '冻结净重=采纳毛重-皮重，公斤，非负',
  adopted_source      VARCHAR(16)  NOT NULL COMMENT '采纳来源：ORIGINAL/REWEIGH',
  adopted_reweigh_id  BIGINT       NULL COMMENT '采纳的复称申请ID；原称量为NULL',
  reweigh_version_set VARCHAR(512) NOT NULL COMMENT '冻结时已提交复称版本集合，逗号分隔的复称ID',
  request_id          VARCHAR(64)  NOT NULL COMMENT '幂等请求ID',
  request_hash        VARCHAR(64)  NOT NULL COMMENT '请求参数哈希',
  frozen_at_utc       TIMESTAMP    NOT NULL COMMENT '冻结时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_freeze_ticket (ticket_id),
  UNIQUE KEY uk_freeze_req (request_id),
  CONSTRAINT fk_freeze_ticket FOREIGN KEY (ticket_id) REFERENCES weigh_ticket (id)
) COMMENT='最终净重冻结快照：冻结后不可覆盖';

CREATE TABLE errata_request (
  id              BIGINT AUTO_INCREMENT COMMENT '勘误申请ID',
  ticket_id       BIGINT       NOT NULL COMMENT '所属称量单ID',
  new_gross_kg    INT          NOT NULL COMMENT '勘误新毛重，公斤；不覆盖冻结快照',
  reason          VARCHAR(256) NOT NULL COMMENT '勘误理由留痕',
  status          VARCHAR(16)  NOT NULL COMMENT 'SUBMITTED已提交',
  request_id      VARCHAR(64)  NOT NULL COMMENT '幂等请求ID',
  request_hash    VARCHAR(64)  NOT NULL COMMENT '请求参数哈希',
  created_at_utc  TIMESTAMP    NOT NULL COMMENT '申请时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_errata_req (request_id),
  CONSTRAINT fk_errata_ticket FOREIGN KEY (ticket_id) REFERENCES weigh_ticket (id)
) COMMENT='勘误申请：冻结后的新称量仅形成勘误，不覆盖结算快照';