package com.example.starter.batch;

/**
 * 抽样检验计划状态。
 * OPEN 已创建、登记未完成且尚未触发拒收；
 * ACCEPTED 样本全部登记完成且累计加权缺陷数不大于 Ac，可作为放行前置；
 * REJECTED 累计加权缺陷数达到 Re，禁止批准，可在限额内新建下一计划。
 * ACCEPTED/REJECTED 均为终结态，计划与逐件结果、判定时刻此后不可改写。
 */
public enum SamplingPlanStatus {
    OPEN,
    ACCEPTED,
    REJECTED
}
