package com.example.starter.archival.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 质检退回结果，回显批次累计的全部退回理由（旧理由保留）。
 *
 * @param batchNo     批次号
 * @param status      退回后批次状态
 * @param reasons     累计退回理由，按时间升序
 * @param rejectedAt  本次退回时间（Asia/Shanghai）
 */
public record RejectResult(String batchNo, String status,
                           List<String> reasons, LocalDateTime rejectedAt) {
}
