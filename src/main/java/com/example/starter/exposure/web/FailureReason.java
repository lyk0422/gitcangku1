package com.example.starter.exposure.web;

/**
 * 业务失败原因码（机器可读），所有失败响应均携带可区分原因。
 */
public final class FailureReason {

    /** 请求时刻缺少覆盖该类别的有效 ALLOW，或命中有效 DENY；不创建预占且不扣频次预算。 */
    public static final String CONSENT_DENIED = "CONSENT_DENIED";
    /** 请求时刻落在公告配置的 UTC 静默窗口 [起点, 终点) 内。 */
    public static final String SILENCE_PERIOD = "SILENCE_PERIOD";
    /** 命中同访客两次有效曝光的最小间隔（冷却频控）。 */
    public static final String FREQUENCY_LIMIT = "FREQUENCY_LIMIT";
    /** 公告当日总额度或访客当日额度不足。 */
    public static final String BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";
    /** 提交的同意版本不大于该 (访客, 类别) 已有最大版本。 */
    public static final String CONSENT_VERSION_CONFLICT = "CONSENT_VERSION_CONFLICT";
    /** 低版本同意不得覆盖尚在生效的高版本（或同版本）DENY。 */
    public static final String CONSENT_DENY_PROTECTED = "CONSENT_DENY_PROTECTED";
    /** 目标同意不存在或已撤回/被覆盖，无法再次撤回。 */
    public static final String CONSENT_NOT_ACTIVE = "CONSENT_NOT_ACTIVE";
    /** 活动版本已被并发修改（CAS 失败）。 */
    public static final String CAMPAIGN_VERSION_CONFLICT = "CAMPAIGN_VERSION_CONFLICT";
    /** 请求体参数缺失、越界或无法解析。 */
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    /** 公告、预占单或同意记录不存在。 */
    public static final String NOT_FOUND = "NOT_FOUND";
    /** 公告编号已存在。 */
    public static final String CAMPAIGN_ALREADY_EXISTS = "CAMPAIGN_ALREADY_EXISTS";
    /** 幂等键复用但参数不同，或同键并发竞争超时。 */
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    /** 预占单当前状态不允许该回执操作（确认/取消）。 */
    public static final String RESERVATION_STATE_CONFLICT = "RESERVATION_STATE_CONFLICT";
    /** 未预期的服务端错误。 */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private FailureReason() {
    }
}
