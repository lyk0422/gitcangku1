package com.example.starter.api.dto;

import java.util.List;

/**
 * 单个改航候选的评估结论。
 *
 * @param index      候选序号（从 1 开始，按请求声明顺序）
 * @param conclusion CLEAR 通过 / BLOCKED 命中禁飞区
 * @param hitZoneIds BLOCKED 时命中的全部 zoneId（字典序去重），CLEAR 为空列表
 * @param points     候选点列（与请求一致，顺序不变）
 */
public record CandidateResultDto(
        int index,
        String conclusion,
        List<String> hitZoneIds,
        List<RoutePointDto> points) {
}
