package com.example.starter.api.dto;

/**
 * 机场时段内的一条当前占用。
 *
 * @param reservationId 预留唯一标识
 * @param routeId       预留所属航线
 * @param priority      优先级，数值越大越高
 * @param windowStart   窗口起点，epoch 毫秒（UTC），左闭
 * @param windowEnd     窗口终点，epoch 毫秒（UTC），右开
 * @param status        当前状态：ACTIVE 占用中（含已起飞）；DISPLACED 不在占用列表中
 * @param departed      是否已起飞；已起飞占用不可被挤出
 */
public record OccupancyDto(
        String reservationId,
        String routeId,
        int priority,
        long windowStart,
        long windowEnd,
        String status,
        boolean departed) {
}
