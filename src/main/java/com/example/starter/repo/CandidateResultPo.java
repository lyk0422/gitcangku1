package com.example.starter.repo;

import com.example.starter.domain.Point;

import java.util.List;

/**
 * 改航候选集评估的单个候选不可变结论。
 *
 * @param index      候选序号（从 1 开始，按请求声明顺序）
 * @param points     候选有序航点
 * @param hitZoneIds 命中的 zoneId（字典序去重）；空列表表示 CLEAR
 */
public record CandidateResultPo(int index, List<Point> points, List<String> hitZoneIds) {
}
