package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 轮换目标名册中的三类职责人员集合；必须为完整名册（每类至少一人），名册换序视为同参。
 *
 * @param dataCollectors           数据采集者编号集合（每类至少一人）
 * @param randomizationCustodians  随机化保管者编号集合（每类至少一人，不得与采集者有交集）
 * @param safetyReviewers          安全审阅者编号集合（每类至少一人）
 */
public record RotationRosterRequest(
        @NotEmpty(message = "dataCollectors 至少一人")
        List<String> dataCollectors,
        @NotEmpty(message = "randomizationCustodians 至少一人")
        List<String> randomizationCustodians,
        @NotEmpty(message = "safetyReviewers 至少一人")
        List<String> safetyReviewers
) {
}
