package com.example.starter.domain;

/**
 * 航班审查拒绝原因码（可区分，随审查明细持久化）。
 */
public final class FlightRejectReason {

    /** NORMAL 航班起降段与关闭窗口相交。 */
    public static final String RUNWAY_CLOSED = "RUNWAY_CLOSED";
    /** EMERGENCY 航班命中的窗口不允许紧急例外。 */
    public static final String EMERGENCY_EXCEPTION_NOT_ALLOWED = "EMERGENCY_EXCEPTION_NOT_ALLOWED";
    /** EMERGENCY 航班命中允许例外的窗口但未附事件号。 */
    public static final String MISSING_EVENT_NO = "MISSING_EVENT_NO";
    /** 跑道小时容量槽位超限。 */
    public static final String CAPACITY_EXCEEDED = "CAPACITY_EXCEEDED";
    /** 航班当前状态不可普通审查（如 RUNWAY_RISK/DEPARTED/CANCELLED/APPROVED）。 */
    public static final String FLIGHT_NOT_REVIEWABLE = "FLIGHT_NOT_REVIEWABLE";

    private FlightRejectReason() {
    }
}
