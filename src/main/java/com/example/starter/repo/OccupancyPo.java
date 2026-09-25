package com.example.starter.repo;

/**
 * 时空桶占用记录。
 *
 * @param bucketKey 所属时空桶键
 * @param routeId   占用航线标识
 * @param reviewId  批准该占用的审核记录标识
 * @param priority  占用航线优先级（NORMAL / EMERGENCY）
 * @param eventNo   EMERGENCY 的事件编号；NORMAL 为 null
 * @param status    APPROVED 未起飞 / DEPARTED 已起飞
 * @param createdAt 占用创建时间（epoch 毫秒）
 */
public record OccupancyPo(String bucketKey, String routeId, String reviewId,
                          String priority, String eventNo, String status, long createdAt) {
}
