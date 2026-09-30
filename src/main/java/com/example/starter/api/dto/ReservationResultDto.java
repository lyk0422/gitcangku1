package com.example.starter.api.dto;

import java.util.List;

/**
 * 预留申请结果。首次成功时携带本次整体挤出的预留标识与不可变替换事件；
 * 同键同参重放时原样返回首次结果（含首次的替换清单）。
 *
 * @param reservationId            新预留唯一标识
 * @param airportId                备降机场标识
 * @param routeId                  申请预留的航线标识
 * @param priority                 优先级，数值越大越高
 * @param windowStart              窗口起点，epoch 毫秒（UTC），左闭
 * @param windowEnd                窗口终点，epoch 毫秒（UTC），右开
 * @param status                   ACTIVE 占用中；DISPLACED 已被挤出
 * @param departed                 是否已起飞
 * @param displacedReservationIds  本次被整体挤出的全部预留标识（无挤出为空列表）
 * @param replacements             本次写入的不可变替换事件列表（顺序确定）
 */
public record ReservationResultDto(
        String reservationId,
        String airportId,
        String routeId,
        int priority,
        long windowStart,
        long windowEnd,
        String status,
        boolean departed,
        List<String> displacedReservationIds,
        List<ReplacementDto> replacements) {
}
