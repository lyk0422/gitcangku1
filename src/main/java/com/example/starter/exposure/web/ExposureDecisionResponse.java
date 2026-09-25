package com.example.starter.exposure.web;

/**
 * 曝光申请联合裁决结果。
 * <ul>
 *     <li>RESERVED：通过抑制名单与两级频控裁决，已创建预占并占用额度，reservation 非空；</li>
 *     <li>SUPPRESSED：当前时刻命中访客抑制区间，不创建预占、不扣频次或预算，
 *     reservation 为空，suppressionReason 非空。</li>
 * </ul>
 *
 * @param outcome           裁决结果：RESERVED 或 SUPPRESED
 * @param reservation       预占单视图；SUPPRESSED 时为 null
 * @param suppressionReason 抑制原因；RESERVED 时为 null
 * @param campaignVersion   裁决时读取到的公告版本（参与请求指纹）
 * @param decidedAtUtc      裁决时刻，epoch 毫秒，UTC
 */
public record ExposureDecisionResponse(
        String outcome,
        ReservationResponse reservation,
        SuppressionReasonResponse suppressionReason,
        int campaignVersion,
        long decidedAtUtc
) {
    public static final String OUTCOME_RESERVED = "RESERVED";
    public static final String OUTCOME_SUPPRESSED = "SUPPRESSED";

    public static ExposureDecisionResponse reserved(ReservationResponse reservation,
                                                    int campaignVersion, long decidedAtUtc) {
        return new ExposureDecisionResponse(
                OUTCOME_RESERVED, reservation, null, campaignVersion, decidedAtUtc);
    }

    public static ExposureDecisionResponse suppressed(SuppressionReasonResponse reason,
                                                      int campaignVersion, long decidedAtUtc) {
        return new ExposureDecisionResponse(
                OUTCOME_SUPPRESSED, null, reason, campaignVersion, decidedAtUtc);
    }
}
