package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 案件保留冻结总览：案件级冻结与案件下全部证物的证物级冻结，
 * 均展示查询时刻的有效状态、UTC 到期时刻与解除链，按发生顺序。
 *
 * @param caseKey 案件键
 * @param holds   案件级与证物级冻结记录
 */
public record CaseHoldsView(
        String caseKey,
        List<HoldView> holds) {
}
