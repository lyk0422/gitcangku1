package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 豁免（双人确认）视图。
 *
 * <p>{@code status} 取值：
 * <ul>
 *     <li>PENDING：仅一名审核人确认，尚不可用于发布；</li>
 *     <li>CONFIRMED：两名不同审核人确认完成，写入不可变双人快照；</li>
 *     <li>REVOKED：已撤销，仅影响后续发布，不改写历史发布快照。</li>
 * </ul>
 *
 * @param exceptionKey 指纹：锁定图版本、漏洞、审核人、到期、理由的摘要
 * @param reviewer1 第一审核人
 * @param reviewer2 第二审核人（与第一审核人不同）；未完成时为 null
 */
public record ExceptionResponse(
        long id,
        String exceptionKey,
        long lockFileId,
        String vulnerabilityId,
        Instant expiresAt,
        String reason,
        String status,
        String reviewer1,
        String reviewer2,
        Instant createdAt,
        Instant confirmedAt) {
}
