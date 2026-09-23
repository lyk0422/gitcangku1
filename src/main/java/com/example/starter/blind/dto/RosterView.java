package com.example.starter.blind.dto;

import java.util.List;

/**
 * 角色名册视图：三类职责角色的人员集合（已排序，名册换序不影响内容）。
 *
 * @param dataCollectors          数据采集者编号集合
 * @param randomizationCustodians 随机化保管者编号集合
 * @param safetyReviewers         安全审阅者编号集合
 */
public record RosterView(
        List<String> dataCollectors,
        List<String> randomizationCustodians,
        List<String> safetyReviewers
) {
}
