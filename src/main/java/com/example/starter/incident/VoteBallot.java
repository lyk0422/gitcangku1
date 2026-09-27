package com.example.starter.incident;

/**
 * 决策投票票型：支持或反对。同一角色对同一提案的同一任务版本仅一票。
 */
public enum VoteBallot {

    /** 支持票；CAST 状态的支持票计入法定人数。 */
    SUPPORT,

    /** 反对票；消耗该角色当次投票权但不计入法定人数。 */
    OPPOSE;
}
