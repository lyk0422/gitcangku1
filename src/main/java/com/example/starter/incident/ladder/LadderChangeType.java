package com.example.starter.incident.ladder;

/**
 * 升级阶梯等级变更类型，对应 incident_ladder_history.change_type。
 * 历史行 append-only、不可变；后续阈值版本修订不回写任何历史行。
 */
public enum LadderChangeType {

    /** 首次登记风险分数并入阶梯；from_level/from_score 为空，to_level 为按阈值计算的初始等级。 */
    INIT,

    /** 批量分数更新被接受但等级未变化（from_level = to_level，分数本身形成不可变证据）。 */
    SCORE_UPDATE,

    /** 分数达到更高阈值触发的自动提升，可一次跨多级；刷新冷却起点与最近升级操作人。 */
    AUTO_ESCALATE,

    /** 人工确认的降级，只能逐级向下（to_level = from_level - 1）；不刷新冷却起点。 */
    DEMOTE
}
