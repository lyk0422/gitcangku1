package com.example.starter.repo;

import com.example.starter.domain.Point;

import java.util.List;

/**
 * 改航评估逐候选不可变结论记录。
 *
 * @param evaluationId 所属评估记录标识
 * @param idx          候选在请求声明顺序中的序号（从 0 开始）
 * @param conclusion   CLEAR / BLOCKED
 * @param hitZoneIds   命中 zoneId（字典序去重）
 * @param points       该候选点列不可变快照
 */
public record EvaluationCandidatePo(String evaluationId, int idx, String conclusion,
                                    List<String> hitZoneIds, List<Point> points) {
}
