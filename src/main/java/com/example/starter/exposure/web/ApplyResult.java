package com.example.starter.exposure.web;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 曝光申请裁决结果：要么创建预占（{@link ReservationResponse}），
 * 要么命中抑制名单被拒绝（{@link SuppressedResponse}，status=SUPPRESSED）。
 * 多态类型用于幂等记录的完整响应存储与重放。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "resultType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ReservationResponse.class, name = "RESERVED"),
        @JsonSubTypes.Type(value = SuppressedResponse.class, name = "SUPPRESSED")
})
public sealed interface ApplyResult permits ReservationResponse, SuppressedResponse {

    /** 公告编号。 */
    String campaignId();

    /** 访客编号。 */
    String visitorId();

    /** 裁决时刻（服务端当前时刻），epoch 毫秒，UTC。 */
    long decidedAtUtc();
}
