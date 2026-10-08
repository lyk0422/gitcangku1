package com.example.starter.race.domain;

/**
 * 中立区间定义的不可变值对象（请求 DTO 与持久行均可映射为此类型后参与纯逻辑校验）。
 *
 * @param startPosition 起点检查点顺序；0 表示发枪零点
 * @param endPosition   终点检查点顺序，严格大于起点
 */
public record NeutralLegSpec(int startPosition, int endPosition) implements NeutralLegView {
}
