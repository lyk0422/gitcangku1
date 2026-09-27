package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 案件维度跨案移交诊断视图。计数均为查询时刻实际值，读取不改变任何状态。
 *
 * @param caseKey             案件键
 * @param totalCount          涉及该案件的移交总数（实际值）
 * @param completedCount      已完成（未撤销）移交数量（实际值）
 * @param revokedCount        已撤销移交数量（实际值）
 * @param chainEntryCount     该案件保管链条目总数（实际值）
 * @param custodians          该案件当前具备保管权限的操作人（按授权顺序）
 */
public record CaseTransferDiagnosticsView(
        String caseKey,
        long totalCount,
        long completedCount,
        long revokedCount,
        long chainEntryCount,
        List<String> custodians) {
}
