package com.example.starter.tag.web;

import com.example.starter.tag.domain.HandoffStatus;

import java.time.Instant;

/**
 * 时间线中的一次站点间交接，含确认时刻或拒绝原因。
 */
public class HandoffEvent {

    private String handoffCode;
    private String fromSiteCode;
    private String toSiteCode;
    private HandoffStatus status;
    private Instant initiatedAt;
    private Instant confirmedAt;
    private Instant closedAt;
    private String rejectReason;

    public String getHandoffCode() {
        return handoffCode;
    }

    public void setHandoffCode(String handoffCode) {
        this.handoffCode = handoffCode;
    }

    public String getFromSiteCode() {
        return fromSiteCode;
    }

    public void setFromSiteCode(String fromSiteCode) {
        this.fromSiteCode = fromSiteCode;
    }

    public String getToSiteCode() {
        return toSiteCode;
    }

    public void setToSiteCode(String toSiteCode) {
        this.toSiteCode = toSiteCode;
    }

    public HandoffStatus getStatus() {
        return status;
    }

    public void setStatus(HandoffStatus status) {
        this.status = status;
    }

    public Instant getInitiatedAt() {
        return initiatedAt;
    }

    public void setInitiatedAt(Instant initiatedAt) {
        this.initiatedAt = initiatedAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }
}
