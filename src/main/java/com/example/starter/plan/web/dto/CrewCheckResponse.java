package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 计划乘务资格核查结果：逐人资格覆盖、占用窗口与冲突计划，成员按业务键升序稳定排序。
 * 已发布（含已取消）计划返回发布当时的资格版本与人员名单快照；草稿按计划当前占用实时评估。
 */
public record CrewCheckResponse(
        String scheduleKey,
        String status,
        Instant journeyStartUtc,
        Instant journeyEndUtc,
        int requiredGrade,
        List<MemberCrewCheck> members) {

    /**
     * 逐人核查结果。
     *
     * @param snapshot  true 表示来自发布时快照（历史依据），false 表示按当前资格实时评估
     */
    public record MemberCrewCheck(
            String memberCode,
            boolean covered,
            String failureReason,
            boolean snapshot,
            Integer qualVersion,
            Integer qualLineGrade,
            Instant qualValidFromUtc,
            Instant qualValidToUtc,
            Instant occupyStartUtc,
            Instant occupyEndUtc,
            List<String> conflictingPlans) {
    }
}