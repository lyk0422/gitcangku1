package com.example.starter.evidence.dto;

/**
 * 披露包证物明细视图（冻结快照）。
 *
 * @param evidenceKey  证物业务键
 * @param seqNo        包内规范排序位次（从 1 开始）
 * @param custodianId  截至时刻重放得到的保管人（位置快照）
 * @param sealIntact   截至时刻封签是否正常
 * @param chainVersion 截至时刻链版本（已生效链事件总数）
 * @param chainDigest  截至时刻重放链事件的 SHA-256 摘要
 */
public record PackageItemView(
        String evidenceKey,
        int seqNo,
        String custodianId,
        boolean sealIntact,
        int chainVersion,
        String chainDigest) {
}
