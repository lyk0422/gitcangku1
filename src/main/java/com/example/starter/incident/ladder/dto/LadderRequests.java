package com.example.starter.incident.ladder.dto;

import java.util.List;

/**
 * 升级阶梯写接口请求体集合。commandKey 为调用方幂等键，全局唯一。
 */
public final class LadderRequests {

    private LadderRequests() {
    }

    /**
     * 发布阈值版本：versionKey 业务键唯一；thresholds 固定 5 个、非负且严格递增；
     * cooldownMinutes 取 0~527040（366 天）。版本行不可变，发布后成为当前生效版本，
     * 不回写既有事件等级与历史。
     */
    public record PublishVersionRequest(String commandKey, String versionKey, List<Long> thresholds,
                                        Integer cooldownMinutes, String note) {
    }

    /** 批量分数更新单项：incidentKey 必须存在，score 为非负整数。 */
    public record BatchScoreItem(String incidentKey, Long score) {
    }

    /**
     * 批量风险分数更新：整批先校验全部事件的最终等级与后态，任一失败整批回滚；
     * items 不允许为空、incidentKey 不允许重复；事件必须处于非 CLOSED 状态。
     */
    public record BatchScoreRequest(String commandKey, List<BatchScoreItem> items) {
    }

    /**
     * 降级确认请求：expectedLevel 为调用方锚定的当前等级（乐观条件，防止跨层级/并发误降）；
     * note 为非空降级理由。操作人由 X-Actor-Id 指定，必须与最近升级操作人不同。
     */
    public record DemoteRequest(String commandKey, Integer expectedLevel, String note) {
    }
}
