package com.example.starter.api.dto;

import jakarta.validation.constraints.Size;

/**
 * 航线起降计划（可选）。时刻均为 UTC epoch 毫秒。
 * 提供跑道标识时必须提供对应时刻；类别缺省为 NORMAL。
 *
 * @param category    航线类别：NORMAL / EMERGENCY；null 按 NORMAL 处理
 * @param eventNo     紧急事件号；EMERGENCY 经关闭窗口例外通过时必填
 * @param depRunwayId 计划起飞跑道标识
 * @param depTimeUtc  计划起飞时刻（UTC epoch 毫秒）
 * @param arrRunwayId 计划降落跑道标识
 * @param arrTimeUtc  计划降落时刻（UTC epoch 毫秒）
 */
public record FlightPlanDto(
        @Size(max = 16) String category,
        @Size(max = 64) String eventNo,
        @Size(max = 64) String depRunwayId,
        Long depTimeUtc,
        @Size(max = 64) String arrRunwayId,
        Long arrTimeUtc) {
}
