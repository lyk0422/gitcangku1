package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单选手的中止补偿明细（只读）。
 *
 * @param bib                 参赛号
 * @param totalCompensationMs 全部已恢复事件的补偿合计毫秒数
 * @param entries             按中止开始点升序的逐事件补偿明细；无已恢复事件为空列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunnerCompensationResponse(
        String bib,
        long totalCompensationMs,
        List<CompensationEntryResponse> entries
) {
}
