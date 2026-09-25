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
    operation     VARCHAR(32)  NOT NULL COMMENT '操作类型：REPLACE_DRAFT / PUBLISH / REVOKE_GRANT / CREATE_OVERRIDE / CANCEL_OVERRIDE / CREATE_BLACKOUT / CREATE_SUBTITLE_TEXT / APPROVE_SUBTITLE_TEXT / CREATE_SUBTITLE / REVOKE_SUBTITLE / PLAYOUT_RECEIPT',
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

-- ========== 紧急字幕（crawl）相关 ==========

CREATE TABLE IF NOT EXISTS playout_blackout (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '黑屏窗口自增 ID',
    channel_id    VARCHAR(64)  NOT NULL COMMENT '黑屏所属频道 ID',
    start_ms      BIGINT       NOT NULL COMMENT '黑屏开始（含），UTC 纪元毫秒，区间左闭右开',
    end_ms        BIGINT       NOT NULL COMMENT '黑屏结束（不含），UTC 纪元毫秒，区间左闭右开',
    regions_text  VARCHAR(2000) NOT NULL COMMENT '规范化区域集合，去重并按字典序排序后逗号拼接；空值含义：不使用（至少一个区域）',
    request_id    VARCHAR(64)  NOT NULL COMMENT '创建操作的幂等请求 ID',
    created_at_ms BIGINT       NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    PRIMARY KEY (id),
    KEY idx_blackout_channel (channel_id, start_ms, end_ms)
) COMMENT = '黑屏窗口表；与紧急字幕窗口在共同区域正重叠时阻断整次发布';

CREATE TABLE IF NOT EXISTS playout_blackout_region (
    blackout_id BIGINT      NOT NULL COMMENT '黑屏窗口 ID',
    region      VARCHAR(64) NOT NULL COMMENT '区域代码，已规范化（去空白、去重、排序由主表冗余）',
    PRIMARY KEY (blackout_id, region)
) COMMENT = '黑屏窗口区域关联表';

CREATE TABLE IF NOT EXISTS playout_subtitle_text (
    text_key           VARCHAR(64)   NOT NULL COMMENT '字幕文本逻辑键，客户端指定，可拥有多个不可变版本',
    version            INT           NOT NULL COMMENT '文本版本，正整数，同一 text_key 下唯一；内容创建后不可变',
    content            VARCHAR(2000) NOT NULL COMMENT '字幕文本内容，创建即冻结',
    status             VARCHAR(16)   NOT NULL COMMENT '审核状态：PENDING 待审核 / APPROVED 已审核；仅 PENDING 可审核，APPROVED 为终态',
    approve_request_id VARCHAR(64)   NULL COMMENT '审核操作的幂等请求 ID；未审核时为 NULL',
    created_at_ms      BIGINT        NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    approved_at_ms     BIGINT        NULL COMMENT '审核通过时间，UTC 纪元毫秒；未审核时为 NULL',
    PRIMARY KEY (text_key, version)
) COMMENT = '字幕文本版本表，内容不可变，审核为单向终态';

CREATE TABLE IF NOT EXISTS playout_emergency_subtitle (
    subtitle_key      VARCHAR(64)   NOT NULL COMMENT '紧急字幕全局唯一键，客户端指定，创建后不变',
    channel_id        VARCHAR(64)   NOT NULL COMMENT '字幕所属频道 ID',
    priority          INT           NOT NULL COMMENT '整数优先级，数值越大优先级越高',
    text_key          VARCHAR(64)   NOT NULL COMMENT '引用的字幕文本逻辑键',
    text_version      INT           NOT NULL COMMENT '引用的文本版本，发布时该版本必须已 APPROVED',
    start_ms          BIGINT        NOT NULL COMMENT '字幕窗口开始（含），UTC 纪元毫秒，左闭右开',
    end_ms            BIGINT        NOT NULL COMMENT '字幕窗口结束（不含），UTC 纪元毫秒，左闭右开',
    regions_text      VARCHAR(2000) NOT NULL COMMENT '规范化区域集合，去重并按字典序排序后逗号拼接',
    status            VARCHAR(16)   NOT NULL COMMENT '状态：ACTIVE 生效中 / REVOKED 已撤销；创建即 ACTIVE，撤销为终态',
    revoke_request_id VARCHAR(64)   NULL COMMENT '撤销操作的幂等请求 ID；未撤销时为 NULL',
    revoked_at_ms     BIGINT        NULL COMMENT '撤销时间，UTC 纪元毫秒；未撤销时为 NULL',
    created_at_ms     BIGINT        NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    PRIMARY KEY (subtitle_key),
    KEY idx_subtitle_playout (channel_id, status, start_ms, end_ms, priority)
) COMMENT = '紧急字幕表；同区域同优先级 ACTIVE 窗口不得重叠（端点相接合法），撤销不改写已发布快照';

CREATE TABLE IF NOT EXISTS playout_emergency_subtitle_region (
    subtitle_key VARCHAR(64) NOT NULL COMMENT '紧急字幕键',
    region       VARCHAR(64) NOT NULL COMMENT '区域代码（规范化后）',
    PRIMARY KEY (subtitle_key, region),
    KEY idx_subtitle_region (region)
) COMMENT = '紧急字幕区域关联表';

CREATE TABLE IF NOT EXISTS playout_publication_subtitle (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '发布固化字幕子片自增 ID',
    publication_id BIGINT        NOT NULL COMMENT '所属发布快照 ID',
    region         VARCHAR(64)   NOT NULL COMMENT '区域代码（规范化后）',
    segment_id     VARCHAR(64)   NOT NULL COMMENT '下层节目片段 ID',
    start_ms       BIGINT        NOT NULL COMMENT '覆盖子片开始（含），UTC 纪元毫秒，左闭右开',
    end_ms         BIGINT        NOT NULL COMMENT '覆盖子片结束（不含），UTC 纪元毫秒，左闭右开',
    subtitle_key   VARCHAR(64)   NOT NULL COMMENT '胜出字幕键，发布时冻结',
    text_key       VARCHAR(64)   NOT NULL COMMENT '胜出字幕文本键，发布时冻结',
    text_version   INT           NOT NULL COMMENT '胜出字幕文本版本，发布时冻结',
    text_content   VARCHAR(2000) NOT NULL COMMENT '胜出字幕文本内容快照，后续文本变更不影响',
    priority       INT           NOT NULL COMMENT '胜出字幕整数优先级，发布时冻结',
    reason         VARCHAR(1000) NOT NULL COMMENT '解析原因，稳定可复现，如候选优先级集合与选中理由',
    PRIMARY KEY (id),
    KEY idx_pub_subtitle (publication_id, region, start_ms, end_ms)
) COMMENT = '发布快照字幕固化表，只读；回执与区域决策均以本表为准';

CREATE TABLE IF NOT EXISTS playout_receipt (
    crawl_key         VARCHAR(64)  NOT NULL COMMENT '播放回执幂等键（客户端生成），同键重放；失败不占键',
    channel_id        VARCHAR(64)  NOT NULL COMMENT '回执确认的频道 ID',
    business_day      DATE         NOT NULL COMMENT '回执时刻所属业务日，Asia/Shanghai 日历日',
    publication_id    BIGINT       NOT NULL COMMENT '确认所依据的发布快照 ID',
    published_version BIGINT       NOT NULL COMMENT '确认所依据的节目单发布版本',
    region            VARCHAR(64)  NOT NULL COMMENT '规范化区域代码',
    at_ms             BIGINT       NOT NULL COMMENT '回执确认时刻，UTC 纪元毫秒',
    asset_id          VARCHAR(64)  NOT NULL COMMENT '快照中的节目素材 ID',
    segment_id        VARCHAR(64)  NOT NULL COMMENT '快照中的节目片段 ID',
    subtitle_key      VARCHAR(64)  NULL COMMENT '命中的固化字幕键；未覆盖字幕时为 NULL',
    text_key          VARCHAR(64)  NULL COMMENT '命中字幕文本键；未覆盖字幕时为 NULL',
    text_version      INT          NULL COMMENT '命中字幕文本版本；未覆盖字幕时为 NULL',
    priority          INT          NULL COMMENT '命中字幕优先级；未覆盖字幕时为 NULL',
    overlay_start_ms  BIGINT       NULL COMMENT '字幕覆盖子片开始（含），UTC 纪元毫秒；未覆盖字幕时为 NULL',
    overlay_end_ms    BIGINT       NULL COMMENT '字幕覆盖子片结束（不含），UTC 纪元毫秒；未覆盖字幕时为 NULL',
    fingerprint       VARCHAR(64)  NOT NULL COMMENT 'crawlKey 指纹：含节目单版本、规范化区域、窗口、优先级、文本版本的 SHA-256',
    created_at_ms     BIGINT       NOT NULL COMMENT '回执创建时间，UTC 纪元毫秒',
    PRIMARY KEY (crawl_key)
) COMMENT = '播放回执表，只按发布快照确认，结束端点恰好时不覆盖字幕';

CREATE TABLE IF NOT EXISTS playout_publish_block (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '发布阻断明细自增 ID',
    channel_id    VARCHAR(64)  NOT NULL COMMENT '被阻断发布的频道 ID',
    business_day  DATE        NOT NULL COMMENT '被阻断发布的业务日，Asia/Shanghai 日历日',
    request_id    VARCHAR(64)  NOT NULL COMMENT '被阻断的发布请求 ID（阻断在独立事务留痕，不占用幂等键）',
    code          VARCHAR(48)  NOT NULL COMMENT '阻断原因码：SUBTITLE_TEXT_NOT_APPROVED / SUBTITLE_BLACKOUT_CONFLICT',
    detail_json   TEXT        NOT NULL COMMENT '阻断明细 JSON：区域、窗口、字幕键、文本版本等，顺序稳定',
    created_at_ms BIGINT      NOT NULL COMMENT '阻断记录时间，UTC 纪元毫秒',
    PRIMARY KEY (id),
    KEY idx_publish_block (channel_id, business_day, id)
) COMMENT = '发布阻断审计表；422 拒绝的稳定区域与窗口明细，发布主事务回滚不影响本表';
