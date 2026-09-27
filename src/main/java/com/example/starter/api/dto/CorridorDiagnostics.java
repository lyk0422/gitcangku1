package com.example.starter.api.dto;

import java.util.List;

/**
 * 走廊时空诊断结果（只读，不改变任何状态）。
 * 返回与指定航线、时段、高度范围（左闭右开）相交的有效走廊与常规占用。
 *
 * @param routeId                     诊断航线标识
 * @param startUtc                    诊断时段起点（含），epoch 毫秒（UTC）
 * @param endUtc                      诊断时段终点（不含），epoch 毫秒（UTC）
 * @param altMin                      诊断高度下限（含），米
 * @param altMax                      诊断高度上限（不含），米
 * @param hitCorridorIds              相交的 ACTIVE 走廊标识（字典序）
 * @param activeRegularOccupationIds  相交的 ACTIVE 常规占用标识（字典序）
 * @param departedOccupationIds       相交的已起飞常规占用标识（字典序）
 */
public record CorridorDiagnostics(String routeId, long startUtc, long endUtc,
                                  int altMin, int altMax,
                                  List<String> hitCorridorIds,
                                  List<String> activeRegularOccupationIds,
                                  List<String> departedOccupationIds) {
}
