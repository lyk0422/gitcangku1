package com.example.starter.repo;

/**
 * 备降机场预留记录（含不可变替换链字段）。
 *
 * @param reservationId 预留唯一标识（首次申请成功时生成）
 * @param airportId     申请占用的备降机场标识
 * @param routeId       申请预留的航线标识
 * @param priority      预留优先级，数值越大优先级越高
 * @param windowStart   UTC 窗口开始（含），epoch 毫秒
 * @param windowEnd     UTC 窗口结束（不含），epoch 毫秒，windowStart &lt; windowEnd
 * @param status        状态：RESERVED 占用中且可被高优先级挤出 / DEPARTED 已起飞仍占容量但不可挤出 / DISPLACED 已被挤出不占容量
 * @param replacedBy    挤出本预留的新预留标识；NULL 表示从未被挤出（不可变）
 * @param requestId     首次申请的写操作请求标识（用于幂等重放）
 * @param createdAt     创建时间，epoch 毫秒（UTC）
 * @param updatedAt    最近一次状态变更时间，epoch 毫秒（UTC）
 */
public record ReservationPo(String reservationId, String airportId, String routeId, int priority,
                            long windowStart, long windowEnd, String status, String replacedBy,
                            String requestId, long createdAt, long updatedAt) {
}
