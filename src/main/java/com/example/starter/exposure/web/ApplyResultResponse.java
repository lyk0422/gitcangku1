package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.ApplyOutcome;

/**
 * 申请曝光结果。静默判定先于额度校验：
 * <ul>
 *     <li>outcome=RESERVED 时携带预占单字段，{@code quietEndsAtUtc} 为 null；</li>
 *     <li>outcome=SUPPRESSED 时仅返回 outcome 与静默结束时刻，预占字段均为 null，
 *     不创建预占、不占两级额度、不返回 429。</li>
 * </ul>
 *
 * @param outcome        裁决结果：RESERVED 或 SUPPRESSED
 * @param reservation    预占单视图；被抑制时为 null
 * @param quietEndsAtUtc 静默结束时刻（epoch 毫秒，UTC）；未抑制时为 null
 */
public record ApplyResultResponse(
        ApplyOutcome outcome,
        ReservationResponse reservation,
        Long quietEndsAtUtc
) {
    public static ApplyResultResponse reserved(ReservationResponse reservation) {
        return new ApplyResultResponse(ApplyOutcome.RESERVED, reservation, null);
    }

    public static ApplyResultResponse suppressed(long quietEndsAtUtc) {
        return new ApplyResultResponse(ApplyOutcome.SUPPRESSED, null, quietEndsAtUtc);
    }
}
