package com.example.starter.firmware.domain;

/**
 * 回滚演练的单台设备结果。演练开始时按规范集合落库（result 为 null），结果提交后不可改。
 *
 * @param id            设备结果ID
 * @param rehearsalId   所属演练ID
 * @param deviceId      设备ID
 * @param ordinal       规范化集合（device_id 升序）中的位置，从 0 开始，明细按它稳定排序
 * @param result        回退结果：SUCCESS/FAILED；结果提交前为 null
 * @param failureReason 失败原因；SUCCESS 或未提交结果时为 null
 */
public record RehearsalDevice(long id, long rehearsalId, String deviceId, int ordinal,
                              ReceiptResult result, String failureReason) {
}
