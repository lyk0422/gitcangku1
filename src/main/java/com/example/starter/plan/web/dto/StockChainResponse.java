package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 按车底查询的交路链明细：按运营日分组、日内按始发时刻升序的已发布段，
 * 以及该车底全部不可变断链记录。
 */
public record StockChainResponse(String stockNo, int minTurnaroundMinutes, int stockVersion,
                                 List<StockChainDay> days, List<ChainBreakView> chainBreaks) {

    /**
     * 单个运营日内的交路链。
     */
    public record StockChainDay(String opDate, List<StockChainItem> segments) {
    }
}
