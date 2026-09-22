package com.example.starter.web.dto;

import com.example.starter.domain.ReservationStatus;

/**
 * 预占明细响应。
 */
public class ReservationResponse {

    private String reservationId;
    private String campaignId;
    private String visitorId;
    /** 额度所属 UTC 日（申请时刻固定），yyyy-MM-dd；确认跨日不迁移。 */
    private String utcDate;
    private ReservationStatus status;
    /** 申请时刻，UTC 毫秒时间戳。 */
    private long createdAt;
    /** 到期时刻（达到即过期），UTC 毫秒时间戳；有效期 60 秒。 */
    private long expiresAt;

    public ReservationResponse() {
    }

    public ReservationResponse(String reservationId, String campaignId, String visitorId, String utcDate,
                               ReservationStatus status, long createdAt, long expiresAt) {
        this.reservationId = reservationId;
        this.campaignId = campaignId;
        this.visitorId = visitorId;
        this.utcDate = utcDate;
        this.status = status;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getReservationId() {
        return reservationId;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public String getVisitorId() {
        return visitorId;
    }

    public String getUtcDate() {
        return utcDate;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }
}
