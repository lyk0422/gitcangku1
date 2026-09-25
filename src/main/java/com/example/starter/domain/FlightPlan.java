package com.example.starter.domain;

/**
 * 航线起降计划（可空；为 null 的航线不参与跑道关闭与容量检查）。
 * 时刻均为 epoch 毫秒（UTC）。
 *
 * @param category    航线类别：NORMAL / EMERGENCY
 * @param eventNo     紧急事件号；EMERGENCY 经关闭窗口例外通过时必填，否则可为 null
 * @param depRunwayId 计划起飞跑道标识；null 表示无起飞计划
 * @param depTimeUtc  计划起飞时刻（UTC epoch 毫秒）
 * @param arrRunwayId 计划降落跑道标识；null 表示无降落计划
 * @param arrTimeUtc  计划降落时刻（UTC epoch 毫秒）
 */
public record FlightPlan(String category, String eventNo,
                         String depRunwayId, Long depTimeUtc,
                         String arrRunwayId, Long arrTimeUtc) {
}
