package com.example.starter.disclosure.dto;

/**
 * 截至时刻重放失败的可区分原因码。
 * NOT_EXIST 证物不存在（404）；FOREIGN_CASE 证物不属于目标案件（422）；
 * SEAL_BROKEN_AT_CUTOFF 截至时刻封签异常（422）；
 * PENDING_TRANSFER_AT_CUTOFF 截至时刻存在未决交接（422）；
 * ACTIVE_LOAN_AT_CUTOFF 截至时刻借出未归还（422）；
 * CUSTODY_GAP_AT_CUTOFF 保管链在截至时刻存在缺口（422）。
 */
public enum CutoffReplayCode {
    NOT_EXIST,
    INTAKE_AFTER_CUTOFF,
    FOREIGN_CASE,
    SEAL_BROKEN_AT_CUTOFF,
    PENDING_TRANSFER_AT_CUTOFF,
    ACTIVE_LOAN_AT_CUTOFF,
    CUSTODY_GAP_AT_CUTOFF
}
