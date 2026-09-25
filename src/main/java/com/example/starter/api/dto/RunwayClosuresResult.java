package com.example.starter.api.dto;

import java.util.List;

/**
 * 跑道关闭窗口查询结果。
 *
 * @param runwayId        跑道标识
 * @param version         当前跑道版本
 * @param capacityPerHour 每小时起降容量（架次）
 * @param closures        全部关闭窗口（按开始时刻升序）
 */
public record RunwayClosuresResult(String runwayId, int version, int capacityPerHour,
                                   List<ClosureWindowDto> closures) {
}
