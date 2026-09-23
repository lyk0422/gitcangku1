package com.example.starter.race.domain;

/**
 * 第二裁决干事的动作：只能确认第一人完全相同的建议，或驳回，不得修改建议内容。
 *
 * <ul>
 *   <li>{@link #CONFIRM}：确认第一干事建议（UPHOLD/REMOVE/REPLACE）；</li>
 *   <li>{@link #REJECT}：驳回，申诉以 REJECTED 终结且不变更处罚。</li>
 * </ul>
 */
public enum AppealSecondAction {
    CONFIRM,
    REJECT
}
