package com.example.starter.common;

/**
 * 业务拒绝原因码，客户端可据此区分失败类型。
 */
public enum ErrorReason {
    /** 请求时隙超出预测可见窗口。 */
    WINDOW_GAP,
    /** 预测最低仰角低于站点门槛。 */
    ELEVATION_BELOW_THRESHOLD,
    /** 时隙时长乘以站点容量容纳不下计划数据量。 */
    CAPACITY_EXCEEDED,
    /** 同站天线在该时段已被其他已确认时隙占用。 */
    ANTENNA_OVERLAP,
    /** 请求基于的预测版本已被修订，不能凭过期预测锁定天线。 */
    STALE_PREDICTION,
    /** 同一幂等键携带了不同参数。 */
    REQUEST_ID_CONFLICT,
    /** 当前状态不允许该操作（如重复完成、取消已完成记录）。 */
    INVALID_STATE,
    /** 完成回报的实际字节数超过计划数据量。 */
    ACTUAL_EXCEEDS_PLANNED,
    /** 引用的资源不存在。 */
    NOT_FOUND
}