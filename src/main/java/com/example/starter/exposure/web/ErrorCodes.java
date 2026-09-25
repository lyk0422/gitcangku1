package com.example.starter.exposure.web;

/**
 * 业务错误码：所有失败路径返回可区分的稳定代码，便于调用方区分
 * 同意拒绝、静默时段、频控/预算不足、状态冲突等原因。
 */
public final class ErrorCodes {

    /** 缺少 ALLOW 同意或命中 DENY：不创建预占、不扣频次与预算。 */
    public static final String CONSENT_DENIED = "CONSENT_DENIED";
    /** 请求时刻落在公告静默时段内。 */
    public static final String SILENT_HOURS = "SILENT_HOURS";
    /** 访客当日频控或公告当日总预算不足。 */
    public static final String QUOTA_EXHAUSTED = "QUOTA_EXHAUSTED";
    /** 资源状态冲突（含非法状态转换、幂等键异参重放）。 */
    public static final String CONFLICT = "CONFLICT";
    /** 资源不存在。 */
    public static final String NOT_FOUND = "NOT_FOUND";
    /** 请求参数非法。 */
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    /** 同意区间违反业务规则：同版本区间重叠，或低版本试图覆盖高版本 DENY。 */
    public static final String CONSENT_CONFLICT = "CONSENT_CONFLICT";
    /** 同意区间时间参数非法（终点不晚于起点等）。 */
    public static final String CONSENT_INVALID_RANGE = "CONSENT_INVALID_RANGE";

    private ErrorCodes() {
    }
}
