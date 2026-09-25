package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 提前终止资质响应：终止后的资质快照与本次写入风险记录的未来已发布计划清单。
 *
 * @param affectedPlans 受影响计划业务键（升序），每个计划均已写入不可变风险记录并置风险门禁
 */
public record TerminateQualificationResponse(
        QualificationResponse qualification,
        List<String> affectedPlans) {
}
