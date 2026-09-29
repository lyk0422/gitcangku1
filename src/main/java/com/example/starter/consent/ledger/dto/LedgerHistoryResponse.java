package com.example.starter.consent.ledger.dto;

import java.time.Instant;
import java.util.List;

/**
 * 账本历史查询结果：UTC 区间左闭右开 {@code [from, to)}，按 seq 升序稳定排序；读取不改变状态。
 *
 * @param subjectKey 过滤主体
 * @param purpose    过滤用途，null 表示不过滤
 * @param from       起始时刻（含，UTC），null 表示不设下界
 * @param to         结束时刻（不含，UTC），null 表示不设上界
 * @param totalCount 命中条目数量（单位：条）
 * @param entries    命中条目
 */
public record LedgerHistoryResponse(
        String subjectKey,
        String purpose,
        Instant from,
        Instant to,
        int totalCount,
        List<LedgerEntryResponse> entries) {
}
