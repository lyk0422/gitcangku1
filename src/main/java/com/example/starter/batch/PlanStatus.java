package com.example.starter.batch;

/**
 * 抽样检验计划状态机。
 * OPEN 为未终结；逐件登记累计加权缺陷达到拒收数 Re 的当件在同一事务内判定 REJECTED；
 * 全部样本登记完成且累计加权缺陷不大于接收数 Ac 判定 ACCEPTED；介于两者之间且未登记完保持 OPEN。
 * 终结（ACCEPTED/REJECTED）后历史计划与判定时刻不可改写。
 */
public enum PlanStatus {
    OPEN,
    ACCEPTED,
    REJECTED
}
