package com.example.starter.incident;

/**
 * 互斥处置分支组状态机：OPEN → CHOSEN。
 * CHOSEN 为终态：已选定唯一成员（DONE）、其余成员已 CANCELLED，
 * 组全集与成员终态冻结，不可变更或再次选择。
 */
public enum BranchGroupStatus {

    /** 待选择：成员全部 OPEN，等待当前指挥人选定唯一分支。 */
    OPEN,

    /** 已选择：选中成员 DONE、其余成员 CANCELLED，组版本已加一，不可再变。 */
    CHOSEN;
}
