package com.example.starter.evidence.dto;

/**
 * 跨案移交诊断视图：校验批次内快照与保管链的数量一致性。只读，不改变任何状态。
 *
 * @param transferId     跨案移交批次标识
 * @param snapshotCount  快照条数
 * @param outCount       来源案件移出链条目数
 * @param inCount        目标案件移入链条目数
 * @param revokeOutCount 撤销反向移出链条目数
 * @param revokeInCount  撤销反向移入链条目数
 * @param revoked        是否已撤销
 * @param balanced       移出/移入/快照数量是否两两相等
 * @param consistent     整体是否一致（balanced 且撤销时反向链同样成对完整）
 */
public record CaseTransferDiagnosticsView(
        String transferId,
        int snapshotCount,
        int outCount,
        int inCount,
        int revokeOutCount,
        int revokeInCount,
        boolean revoked,
        boolean balanced,
        boolean consistent) {
}
