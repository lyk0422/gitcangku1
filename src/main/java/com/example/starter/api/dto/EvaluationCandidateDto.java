package com.example.starter.api.dto;

import java.util.List;

/**
 * 改航评估中单个候选的不可变结论。
 *
 * @param index      候选在请求声明顺序中的序号，从 0 开始
 * @param conclusion CLEAR / BLOCKED
 * @param hitZoneIds 该候选命中的全部 zoneId（字典序去重），未命中为空列表
 * @param points     该候选的有序点列快照
 */
public record EvaluationCandidateDto(
        int index,
        String conclusion,
        List<String> hitZoneIds,
        List<RoutePointDto> points) {
}
