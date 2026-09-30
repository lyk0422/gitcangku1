package com.example.starter.tag.domain;

import java.time.Instant;

/**
 * 站点间标签交接记录。
 *
 * <p>交接由当前持有站点发起（{@link #status}=PENDING），接收站点确认后变为
 * CONFIRMED 并转移标签持有权；确认前可由发起方取消（CANCELLED），
 * 接收方也可拒绝（REJECTED）并记录 {@link #rejectReason}。
 * 所有时刻均为 UTC。
 */
public class Handoff {

    private Long id;
    private String handoffCode;
    private Long tagId;
    private Long fromSiteId;
    private Long toSiteId;
    private HandoffStatus status;
    /** 发起时刻（UTC）。 */
    private Instant initiatedAt;
    /** 确认时刻（UTC）；未确认/取消/拒绝时为 null。 */
    private Instant confirmedAt;
    /** 取消或拒绝时刻（UTC）；PENDING/CONFIRMED 下按语义为 null。 */
    private Instant closedAt;
    /** 拒绝原因；取消或确认时为 null。 */
    private String rejectReason;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getHandoffCode() {
        return handoffCode;
    }

    public void setHandoffCode(String handoffCode) {
        this.handoffCode = handoffCode;
    }

    public Long getTagId() {
        return tagId;
    }

    public void setTagId(Long tagId) {
        this.tagId = tagId;
    }

    public Long getFromSiteId() {
        return fromSiteId;
    }

    public void setFromSiteId(Long fromSiteId) {
        this.fromSiteId = fromSiteId;
    }

    public Long getToSiteId() {
        return toSiteId;
    }

    public void setToSiteId(Long toSiteId) {
        this.toSiteId = toSiteId;
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
