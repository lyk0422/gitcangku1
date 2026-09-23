package com.example.starter.race.api;

import com.example.starter.race.domain.NetTimingCalculator;

/**
 * 单选手在单个中止事件上的补偿明细。
 *
 * @param bib            参赛号
 * @param compensationMs 补偿毫秒数：受影响已起跑者为中止时长，其余为0
 * @param basis          补偿依据：PASSED_CHECKPOINT-中止前已通过指定检查点，
 *                       AFFECTED-已起跑且未通过指定检查点，NOT_STARTED-中止前未起跑
 */
public record RunnerCompensationResponse(
        String bib,
        long compensationMs,
        NetTimingCalculator.CompensationBasis basis
) {
}
