package com.example.starter.tag.web;

import com.example.starter.tag.domain.HandoffStatus;

import java.time.Instant;

/**
 * 交接单结果。
 */
public class HandoffResponse {

    private String handoffCode;
    private String tagCode;
    private String fromSiteCode;
    private String toSiteCode;
    private HandoffStatus status;
    private Instant initiatedAt;
    private Instant confirmedAt;
    private String rejectReason;
    private boolean replay;

    public String getHandoffCode() {
        return handoffCode;
    }

    public void setHandoffCode(String handoffCode) {
        this.handoffCode = handoffCode;
    }

    public String getTagCode() {
        return tagCode;
    }

    public void setTagCode(String tagCode) {
        this.tagCode = tagCode;
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

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public boolean isReplay() {
        return replay;
    }

    public void setReplay(boolean replay) {
        this.replay = replay;
    }
}
