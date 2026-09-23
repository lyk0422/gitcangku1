package com.example.starter.blind.dto;

import java.util.List;

/**
 * 角色名册视图：三类职责的人员集合，序列化前已按编号排序（名册换序视为同参）。
 *
 * @param dataCollectors          数据采集人员
 * @param randomizationCustodians 随机化保管人员
 * @param safetyReviewers         安全审阅人员
 */
public record RosterView(
        List<String> dataCollectors,
        List<String> randomizationCustodians,
        List<String> safetyReviewers
) {
}
