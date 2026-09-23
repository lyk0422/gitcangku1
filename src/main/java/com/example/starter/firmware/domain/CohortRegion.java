package com.example.starter.firmware.domain;

/**
 * 投放活动的区域配额。同一活动内同区域各队列设备数之和不得超过 {@code quota}。
 *
 * @param id         区域配额ID
 * @param releaseId  所属投放活动ID
 * @param regionCode 区域编码，同一活动内唯一
 * @param quota      区域设备总配额，取值 &gt;= 1
 */
public record CohortRegion(long id, long releaseId, String regionCode, int quota) {
}
