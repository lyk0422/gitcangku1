package com.example.starter.api.dto;

import java.util.List;

/**
 * 跑道关闭窗口查询结果。
 *
 * @param runwayId 跑道唯一标识
 * @param version  当前跑道版本
 * @param windows  全部关闭窗口（按开始时刻升序）
 */
public record RunwayWindowsResult(String runwayId, int version, List<ClosureResult> windows) {
}
