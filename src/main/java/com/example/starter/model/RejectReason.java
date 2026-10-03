package com.example.starter.model;

/**
 * 业务拒绝原因码，随 409 响应返回，便于调用方区分。
 */
public enum RejectReason {
    /** 请求时隙超出预测可见窗口，存在窗口缺口。 */
    WINDOW_GAP,
    /** 时隙时长乘以容量快照不足以容纳计划数据量。 */
    CAPACITY_EXCEEDED,
    /** 预测最低仰角未达到站点门槛。 */
    ELEVATION_BELOW_THRESHOLD,
    /** 同一天线在该时段已存在已确认时隙。 */
    ANTENNA_OVERLAP,
    /** 同一 requestId 携带了不同参数。 */
    IDEMPOTENCY_CONFLICT,
    /** 请求基于已被修订取代的预测版本。 */
    STALE_PREDICTION,
    /** 当前状态不允许该操作（如已完成又取消）。 */
    INVALID_STATE,
    /** 实际字节数超过计划数据量。 */
    ACTUAL_BYTES_EXCEED_PLANNED,
    /** 引用的资源不存在。 */
    NOT_FOUND
}