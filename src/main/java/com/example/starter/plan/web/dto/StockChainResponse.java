package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 指定车底在指定运营日的交路链明细，段按始发时刻升序。
 */
public record StockChainResponse(String stockKey, LocalDate opDate, int minTurnaroundMinutes,
                                 List<ChainSegmentView> segments) {
}
