package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 赛事当前芯片绑定诊断查询响应（只读）：每枚芯片当前唯一绑定的未完赛选手。
 *
 * @param raceId   所属赛事ID
 * @param version  查询时赛事版本（只读查询不推进版本）
 * @param bindings 当前芯片绑定，按芯片代码字典序稳定返回
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChipBindingsResponse(
        String raceId,
        int version,
        List<Binding> bindings
) {

    /**
     * 单条当前芯片绑定。
     *
     * @param chipCode 芯片代码
     * @param bib      当前绑定选手参赛号
     * @param boundAtMs 本次绑定建立时间，Unix毫秒时间戳（UTC）
     */
    public record Binding(String chipCode, String bib, long boundAtMs) {
    }
}
