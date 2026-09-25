package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布 / 取消计划请求，携带幂等键。
 *
 * @param requestKey 幂等键，同键同参重放首次结果，异参 409，失败不占键
 * @param preemptKey 可选；非空时本次发布按抢占模式执行：冲突的已发布计划若全部占用区段
 *                   等级都低于本计划，则同一事务内将其转为 PREEMPTED 并发布本计划，
 *                   否则按未满足抢占条件返回 422。空串视为未提交。
 */
public record PlanActionRequest(@NotBlank String requestKey, String preemptKey) {
}
