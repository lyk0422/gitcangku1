package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 跨案移交诊断视图（只读，不改变状态）。数量与时长均返回实际值：
 * evidenceCount 为批次实际证物数；orderRemainingSeconds 为令版本剩余有效秒数（实际值），
 * 令版本不存在或已失效时为 null。
 *
 * @param transferId             跨案移交业务键
 * @param status                 批次状态
 * @param evidenceCount          本批证物实际数量
 * @param orderVersion           移交令版本
 * @param orderValidNow          诊断时刻令版本是否处于有效期（UTC 左闭右开）
 * @param orderRemainingSeconds  令版本剩余有效秒数；已失效或不存在时为 null
 * @param sourceLinkCount        来源案件链事件实际条数
 * @param targetLinkCount        目标案件链事件实际条数
 * @param diagnosedAt            诊断时刻（UTC）
 */
public record CaseTransferDiagView(
        String transferId,
        String status,
        int evidenceCount,
        String orderVersion,
        boolean orderValidNow,
        Long orderRemainingSeconds,
        int sourceLinkCount,
        int targetLinkCount,
        LocalDateTime diagnosedAt) {
}
