-- 播出编排与授权回退业务表结构（MySQL 8）。
-- 时间约定：所有 *_ms 列为 UTC 纪元毫秒（BIGINT），API 层统一使用 Asia/Shanghai、毫秒精度 ISO 8601；
-- 业务日 business_day 为 Asia/Shanghai 时区下的日历日。

CREATE TABLE IF NOT EXISTS playout_asset (
    id           VARCHAR(64)  NOT NULL COMMENT '素材稳定 ID，客户端指定或系统生成，创建后不变',
    duration_ms  BIGINT       NOT NULL COMMENT '素材时长，单位毫秒，正整数',
    created_at_ms BIGINT      NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    PRIMARY KEY (id)
) COMMENT = '素材表';

CREATE TABLE IF NOT EXISTS playout_channel (
    id                VARCHAR(64) NOT NULL COMMENT '频道 ID，客户端指定或系统生成',
    fallback_asset_id VARCHAR(64) NOT NULL COMMENT '保底素材 ID，创建时指定且不会被撤销',
    created_at_ms     BIGINT      NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    PRIMARY KEY (id)
) COMMENT = '频道表';

CREATE TABLE IF NOT EXISTS playout_grant (
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '授权自增 ID',
    channel_id        VARCHAR(64) NOT NULL COMMENT '授权频道 ID',
    asset_id          VARCHAR(64) NOT NULL COMMENT '授权普通素材 ID（不得为频道保底素材）',
    valid_from_ms     BIGINT      NOT NULL COMMENT '授权生效起点（含），UTC 纪元毫秒',
    valid_to_ms       BIGINT      NOT NULL COMMENT '授权生效终点（不含），UTC 纪元毫秒，区间左闭右开',
    revoked           TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否已撤销：0 未撤销，1 已撤销；撤销为终态',
    revoke_request_id VARCHAR(64) NULL COMMENT '撤销操作的幂等请求 ID；未撤销时为 NULL',
    revoked_at_ms     BIGINT      NULL COMMENT '撤销时间，UTC 纪元毫秒；未撤销时为 NULL',
    created_at_ms     BIGINT      NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    PRIMARY KEY (id),
    KEY idx_grant_coverage (channel_id, asset_id, revoked, valid_from_ms, valid_to_ms)
) COMMENT = '素材授权表，授权创建后只能撤销';

CREATE TABLE IF NOT EXISTS playout_draft (
    channel_id    VARCHAR(64) NOT NULL COMMENT '频道 ID',
    business_day  DATE        NOT NULL COMMENT '业务日，Asia/Shanghai 日历日',
    version       BIGINT      NOT NULL COMMENT '草稿乐观锁版本，每次整份替换递增，初始为 1',
    updated_at_ms BIGINT      NOT NULL COMMENT '最近替换时间，UTC 纪元毫秒',
    PRIMARY KEY (channel_id, business_day)
) COMMENT = '播出草稿表，每个频道+业务日一份';

CREATE TABLE IF NOT EXISTS playout_draft_segment (
    id           VARCHAR(64) NOT NULL COMMENT '片段 ID，客户端指定或系统生成',
    channel_id   VARCHAR(64) NOT NULL COMMENT '所属频道 ID',
    business_day DATE        NOT NULL COMMENT '所属业务日，Asia/Shanghai 日历日',
    asset_id     VARCHAR(64) NOT NULL COMMENT '播出素材 ID',
    start_ms     BIGINT      NOT NULL COMMENT '播出开始（含），UTC 纪元毫秒，不得跨日',
    end_ms       BIGINT      NOT NULL COMMENT '播出结束（不含），UTC 纪元毫秒，不得跨日',
    PRIMARY KEY (id),
    KEY idx_draft_segment (channel_id, business_day)
) COMMENT = '草稿片段表，随草稿整份替换而整体重建';

CREATE TABLE IF NOT EXISTS playout_publication (
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '发布快照自增 ID',
    channel_id        VARCHAR(64) NOT NULL COMMENT '频道 ID',
    business_day      DATE        NOT NULL COMMENT '业务日，Asia/Shanghai 日历日',
    published_version BIGINT      NOT NULL COMMENT '发布版本，每频道+业务日从 1 起递增',
    draft_version     BIGINT      NOT NULL COMMENT '发布时对应的草稿版本',
    created_at_ms     BIGINT      NOT NULL COMMENT '发布时间，UTC 纪元毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_publication_version (channel_id, business_day, published_version)
) COMMENT = '发布快照表，快照只读，授权撤销不改写历史快照';

CREATE TABLE IF NOT EXISTS playout_publication_segment (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '快照片段自增 ID',
    publication_id BIGINT      NOT NULL COMMENT '所属发布快照 ID',
    segment_id     VARCHAR(64) NOT NULL COMMENT '来源草稿片段 ID',
    asset_id       VARCHAR(64) NOT NULL COMMENT '播出素材 ID',
    grant_id       BIGINT      NOT NULL COMMENT '发布时完整覆盖该片段的授权 ID，播出查询据此实时判定撤销状态',
    start_ms       BIGINT      NOT NULL COMMENT '播出开始（含），UTC 纪元毫秒',
    end_ms         BIGINT      NOT NULL COMMENT '播出结束（不含），UTC 纪元毫秒',
    PRIMARY KEY (id),
    KEY idx_pub_segment (publication_id)
) COMMENT = '发布快照片段表，只读';

CREATE TABLE IF NOT EXISTS playout_request (
    request_id    VARCHAR(64)  NOT NULL COMMENT '幂等请求 ID，客户端生成',
    operation     VARCHAR(32)  NOT NULL COMMENT '操作类型：REPLACE_DRAFT / PUBLISH / REVOKE_GRANT / CREATE_OVERRIDE / CANCEL_OVERRIDE',
    params_hash   VARCHAR(64)  NOT NULL COMMENT '业务参数（不含 requestId）的 SHA-256，十六进制',
    response_body TEXT         NULL COMMENT '成功时的响应 JSON；失败请求回滚不占用 requestId',
    created_at_ms BIGINT       NOT NULL COMMENT '记录创建时间，UTC 纪元毫秒',
    PRIMARY KEY (request_id)
) COMMENT = '幂等去重表，与业务结果同事务提交';

CREATE TABLE IF NOT EXISTS playout_emergency_override (
    override_key      VARCHAR(64)  NOT NULL COMMENT '紧急插播全局唯一键，客户端指定，创建后不变',
    channel_id        VARCHAR(64) NOT NULL COMMENT '插播频道 ID',
    asset_id          VARCHAR(64) NOT NULL COMMENT '插播普通素材 ID（不得为频道保底素材）',
    grant_id          BIGINT       NOT NULL COMMENT '创建时指定的授权 ID，须属于该频道与素材并完整覆盖区间；撤销后不自动换绑',
    priority          TINYINT      NOT NULL COMMENT '优先级，1～9，数字越大优先级越高',
    start_ms          BIGINT       NOT NULL COMMENT '插播开始（含），UTC 纪元毫秒',
    end_ms            BIGINT       NOT NULL COMMENT '插播结束（不含），UTC 纪元毫秒；区间左闭右开',
    business_day      DATE         NOT NULL COMMENT '插播所在业务日，Asia/Shanghai 日历日；区间不跨日',
    status            VARCHAR(16)  NOT NULL COMMENT '状态：ACTIVE 生效中 / CANCELLED 已取消；创建即 ACTIVE，只能取消不可改写',
    cancel_request_id VARCHAR(64)  NULL COMMENT '取消操作的幂等请求 ID；未取消时为 NULL',
    cancelled_at_ms   BIGINT       NULL COMMENT '取消时间，UTC 纪元毫秒；未取消时为 NULL',
    created_at_ms     BIGINT       NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    PRIMARY KEY (override_key),
    KEY idx_override_playout (channel_id, status, start_ms, end_ms, priority)
) COMMENT = '限时紧急插播表；不改写日草稿与发布快照，同频道同优先级 ACTIVE 区间不得重叠';

CREATE TABLE IF NOT EXISTS playout_caption_text_version (
    version_id     VARCHAR(128) NOT NULL COMMENT '字幕文本版本 ID，客户端指定，全局唯一，内容不可变',
    content        VARCHAR(4000) NOT NULL COMMENT '字幕文本内容，创建后冻结',
    review_status  VARCHAR(16)  NOT NULL COMMENT '审核状态：PENDING 待审核 / APPROVED 已通过 / REJECTED 已驳回；仅 APPROVED 可发布',
    reviewed_at_ms BIGINT       NULL COMMENT '审核时间，UTC 纪元毫秒；未审核时为 NULL',
    created_at_ms  BIGINT       NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    PRIMARY KEY (version_id)
) COMMENT = '字幕文本版本表，内容只增不改，审核状态 PENDING 只能审核一次';

CREATE TABLE IF NOT EXISTS playout_emergency_caption (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '紧急字幕自增 ID',
    caption_key       VARCHAR(64)  NOT NULL COMMENT '紧急字幕客户端键，全局唯一；撤销不复活',
    channel_id        VARCHAR(64)  NOT NULL COMMENT '字幕所属频道 ID',
    priority          INT          NOT NULL COMMENT '整数优先级，数值越大优先级越高',
    text_version_id   VARCHAR(128) NOT NULL COMMENT '字幕文本版本 ID，发布快照按其冻结；撤销字幕不改写已发布快照',
    start_ms          BIGINT       NOT NULL COMMENT 'UTC 窗口起点（含），UTC 纪元毫秒',
    end_ms            BIGINT       NOT NULL COMMENT 'UTC 窗口终点（不含），UTC 纪元毫秒；左闭右开',
    regions_csv       VARCHAR(2048) NOT NULL COMMENT '规范化后的区域集合，字典序升序逗号分隔；空区域不合法',
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE 生效中 / REVOKED 已撤销；创建即 ACTIVE，撤销为终态',
    revoke_request_id VARCHAR(96)  NULL COMMENT '撤销操作的 crawlKey；未撤销时为 NULL',
    revoked_at_ms     BIGINT       NULL COMMENT '撤销时间，UTC 纪元毫秒；未撤销时为 NULL',
    created_at_ms     BIGINT       NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_caption_key (caption_key),
    KEY idx_caption_playout (channel_id, status, start_ms, end_ms, priority)
) COMMENT = '紧急字幕表；同区域同优先级 ACTIVE 有效窗口不得重叠，端点相接合法';

CREATE TABLE IF NOT EXISTS playout_emergency_caption_region (
    caption_id BIGINT      NOT NULL COMMENT '紧急字幕自增 ID',
    region     VARCHAR(64) NOT NULL COMMENT '区域标识，与 caption_id 联合唯一',
    PRIMARY KEY (caption_id, region),
    KEY idx_caption_region (region, caption_id)
) COMMENT = '紧急字幕区域集合表，存储已排序去重的区域';

CREATE TABLE IF NOT EXISTS playout_blackout_window (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '黑屏窗口自增 ID',
    blackout_key  VARCHAR(64) NOT NULL COMMENT '黑屏窗口客户端键，全局唯一',
    channel_id    VARCHAR(64) NOT NULL COMMENT '黑屏所属频道 ID',
    region        VARCHAR(64) NOT NULL COMMENT '黑屏作用区域',
    start_ms      BIGINT      NOT NULL COMMENT '黑屏起点（含），UTC 纪元毫秒',
    end_ms        BIGINT      NOT NULL COMMENT '黑屏终点（不含），UTC 纪元毫秒；左闭右开',
    created_at_ms BIGINT      NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_blackout_key (blackout_key),
    KEY idx_blackout_lookup (channel_id, region, start_ms, end_ms)
) COMMENT = '黑屏窗口表；字幕窗口与黑屏窗口重叠时整次发布 422 拒绝';

CREATE TABLE IF NOT EXISTS playout_crawl_record (
    crawl_key     VARCHAR(96) NOT NULL COMMENT '幂等指纹键 crawlKey，客户端生成；成功才占键，失败回滚不占键',
    operation     VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE_CAPTION / REVIEW_TEXT / CREATE_BLACKOUT / PUBLISH / CONFIRM_PLAYOUT / REVOKE_CAPTION',
    params_hash   VARCHAR(64) NOT NULL COMMENT '业务参数（不含 crawlKey）的 SHA-256，十六进制',
    response_body TEXT        NULL COMMENT '成功时响应 JSON；NULL 表示尚未成功完成',
    created_at_ms BIGINT      NOT NULL COMMENT '记录创建时间，UTC 纪元毫秒',
    PRIMARY KEY (crawl_key)
) COMMENT = 'crawlKey 幂等去重表，与业务结果同事务提交';

CREATE TABLE IF NOT EXISTS playout_publication_caption (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '快照字幕决策自增 ID',
    publication_id  BIGINT       NOT NULL COMMENT '所属发布快照 ID',
    region          VARCHAR(64)  NOT NULL COMMENT '区域标识',
    segment_id      VARCHAR(64)  NOT NULL COMMENT '对应快照片段 ID（该区域该时间片的节目素材）',
    start_ms        BIGINT       NOT NULL COMMENT '时间片起点（含），UTC 纪元毫秒',
    end_ms          BIGINT       NOT NULL COMMENT '时间片终点（不含），UTC 纪元毫秒',
    caption_id      BIGINT       NOT NULL COMMENT '命中的紧急字幕自增 ID（发布时冻结）',
    caption_key     VARCHAR(64)  NOT NULL COMMENT '命中的紧急字幕客户端键（发布时冻结）',
    priority        INT          NOT NULL COMMENT '命中字幕的整数优先级（发布时冻结）',
    text_version_id VARCHAR(128) NOT NULL COMMENT '命中字幕的文本版本 ID（发布时冻结，后续改稿不影响）',
    text_content    VARCHAR(4000) NOT NULL COMMENT '命中字幕文本内容快照（发布时冻结）',
    reason          VARCHAR(48)  NOT NULL COMMENT '解析原因：CAPTION_WIN 窗口内最高优先级 / CAPTION_BOUNDARY 端点切片解析',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pub_caption_slot (publication_id, region, start_ms, end_ms),
    KEY idx_pub_caption (publication_id, region)
) COMMENT = '发布快照字幕决策表，只读；撤销字幕、改稿、新建更高优先级字幕均不改写';

CREATE TABLE IF NOT EXISTS playout_playout_receipt (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '播放回执自增 ID',
    crawl_key          VARCHAR(96)  NOT NULL COMMENT '确认操作的 crawlKey，全局唯一，重放返回首次回执',
    channel_id         VARCHAR(64)  NOT NULL COMMENT '频道 ID',
    publication_id     BIGINT       NOT NULL COMMENT '确认所依据的发布快照 ID',
    published_version  BIGINT       NOT NULL COMMENT '确认所依据的发布版本',
    region             VARCHAR(64)  NOT NULL COMMENT '确认区域',
    at_ms              BIGINT       NOT NULL COMMENT '确认时刻，UTC 纪元毫秒',
    asset_id           VARCHAR(64)  NOT NULL COMMENT '快照确认的节目素材 ID',
    segment_id         VARCHAR(64)  NOT NULL COMMENT '快照确认的节目片段 ID',
    caption_record_id  BIGINT       NULL COMMENT '确认的快照字幕决策 ID；该时刻无字幕覆盖时为 NULL',
    caption_key        VARCHAR(64)  NULL COMMENT '确认字幕键快照；无覆盖时为 NULL',
    priority           INT          NULL COMMENT '确认字幕优先级快照；无覆盖时为 NULL',
    text_version_id    VARCHAR(128) NULL COMMENT '确认字幕文本版本 ID 快照；无覆盖时为 NULL',
    caption_text       VARCHAR(4000) NULL COMMENT '确认字幕文本快照；无覆盖时为 NULL',
    caption_start_ms   BIGINT       NULL COMMENT '字幕窗口起点（含），UTC 纪元毫秒；无覆盖时为 NULL',
    caption_end_ms     BIGINT       NULL COMMENT '字幕窗口终点（不含），UTC 纪元毫秒；确认时刻恰为终点时不再覆盖，为 NULL',
    confirm_reason     VARCHAR(48)  NOT NULL COMMENT '确认原因：CAPTION_CONFIRMED 字幕覆盖 / NO_CAPTION 无字幕 / CAPTION_END_EXACT 恰为字幕结束端点',
    created_at_ms      BIGINT       NOT NULL COMMENT '回执创建时间，UTC 纪元毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_receipt_crawl_key (crawl_key),
    KEY idx_receipt_publication (publication_id, region)
) COMMENT = '播放回执表，按发布快照确认；字幕结束端点恰好时不再覆盖';
