package com.example.starter.incident;

/**
 * 互斥分支组状态机：OPEN → CHOSEN。
 * CHOSEN 为终态：已选组不可变更成员，也不允许再次选择。
 */
public enum BranchGroupStatus {

    /** 待选择：成员任务仍 OPEN，等待整组选择。 */
    OPEN,

    /** 已完成选择：选中成员 DONE、其余成员 CANCELLED，组版本已加一。 */
    CHOSEN;
}
