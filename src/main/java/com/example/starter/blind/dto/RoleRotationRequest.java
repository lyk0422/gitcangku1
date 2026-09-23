package com.example.starter.blind.dto;

import java.util.List;

/**
 * 职责轮换请求体（预览与激活共用；预览时 rotationKey 可缺省）。
 *
 * @param rotationKey               轮换单号，激活必填，全局唯一
 * @param expectedExperimentVersion 期望实验版本号，激活时与当前版本比对
 * @param effectiveAt               新授权代次生效时刻，Unix 毫秒，UTC
 * @param dataCollectors            目标数据采集人员集合（至少一人）
 * @param randomizationCustodians   目标随机化保管人员集合（至少一人）
 * @param safetyReviewers           目标安全审阅人员集合（至少一人）
 */
public record RoleRotationRequest(
        String rotationKey,
        Long expectedExperimentVersion,
        Long effectiveAt,
        List<String> dataCollectors,
        List<String> randomizationCustodians,
        List<String> safetyReviewers
) {
}
