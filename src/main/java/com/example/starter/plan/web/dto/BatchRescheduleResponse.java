package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 批量改签响应：整批在同一事务提交，全部成功；结果按请求 items 顺序返回。
 *
 * @param requestKey 整批幂等键
 * @param results    每项改签后的旧/新计划快照，顺序与请求一致
 */
public record BatchRescheduleResponse(String requestKey, List<BatchItemResult> results) {

    /**
     * 单项改签结果：旧计划（已取消，历史保留）与新计划（已发布）。
     */
    public record BatchItemResult(String oldScheduleKey, String newScheduleKey,
                                  PlanResponse oldPlan, PlanResponse newPlan) {
    }
}
