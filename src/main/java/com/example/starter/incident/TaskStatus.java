package com.example.starter.incident;

/**
 * 处置任务状态机。
 * 未开始：OPEN / DISPATCHED / EVACUATION_BLOCKED；进行中：IN_PROGRESS；
 * 终态：DONE / CANCELLED / EVACUATED。
 *
 * <p>流转：
 * <ul>
 *   <li>OPEN/DISPATCHED → DONE 或 CANCELLED（原门禁，完成另见疏散门禁）；</li>
 *   <li>OPEN/DISPATCHED → IN_PROGRESS（开始，高危任务须持豁免）；</li>
 *   <li>OPEN/DISPATCHED → EVACUATION_BLOCKED（区域生效命中，固化区域快照）；</li>
 *   <li>EVACUATION_BLOCKED → OPEN（区域结束后恢复，仍须满足其余原门禁）；</li>
 *   <li>IN_PROGRESS → DONE（持有效豁免或未命中时）或 EVACUATED（撤离登记，终态）。</li>
 * </ul>
 */
public enum TaskStatus {

    /** 待开始（单任务创建后的初始状态；EVACUATION_BLOCKED 区域结束后也恢复为此状态）。 */
    OPEN,

    /** 已派工（批量派工后、开始前）。 */
    DISPATCHED,

    /** 进行中（已开始）。 */
    IN_PROGRESS,

    /** 已完成。 */
    DONE,

    /** 已取消。 */
    CANCELLED,

    /** 疏散阻断：未开始任务命中有效疏散区域且无对应版本豁免，区域快照已固化。 */
    EVACUATION_BLOCKED,

    /** 已撤离：进行中命中任务登记撤离后的终态，不可完成。 */
    EVACUATED;

    /**
     * 是否为“未开始”状态（含派工与疏散阻断）。
     */
    public boolean notStarted() {
        return this == OPEN || this == DISPATCHED || this == EVACUATION_BLOCKED;
    }

    /**
     * 是否为终态（不允许任何后续流转）。
     */
    public boolean isTerminal() {
        return this == DONE || this == CANCELLED || this == EVACUATED;
    }
}
