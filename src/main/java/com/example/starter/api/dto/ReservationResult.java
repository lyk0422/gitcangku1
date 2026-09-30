package com.example.starter.api.dto;

import java.util.List;

/**
 * 预留申请结果。成功时携带新预留标识与本次被整体挤出的低优先级预留清单；
 * 幂等重放时原样返回首次的挤出清单。
 *
 * @param reservationId 新预留唯一标识
 * @param airportId     占用的备降机场标识
 * @param routeId       申请航线标识
 * @param priority      预留优先级，数值越大越高
 * @param windowStart   UTC 窗口开始（含），epoch 毫秒
 * @param windowEnd     UTC 窗口结束（不含），epoch 毫秒
 * @param status        预留当前状态：RESERVED / DEPARTED / DISPLACED
 * @param displaced     被本次申请整体挤出的预留清单（按预留标识排序），无挤出为空列表
 */
public record ReservationResult(String reservationId, String airportId, String routeId,
                                int priority, long windowStart, long windowEnd, String status,
                                List<DisplacedReservationDto> displaced) {
}
