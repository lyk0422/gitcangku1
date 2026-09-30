package com.example.starter.model;

import com.example.starter.domain.RequestStatus;

import java.time.Instant;

/**
 * 观测窗口申请持久化对象。时间均为 UTC，窗口 [{@code windowStart}, {@code windowEnd}) 左闭右开。
 */
public class ObservationRequest {

    private Long id;

    /** 申请编号，候补排序次关键字（升序）。 */
    private String requestNo;

    /** 项目名称。 */
    private String project;

    /** 仪器编号。 */
    private String instrumentId;

    /** 归属研究组，撤销鉴权依据。 */
    private String researchGroup;

    /** 窗口开始时刻（含），UTC。 */
    private Instant windowStart;

    /** 窗口结束时刻（不含），UTC。 */
    private Instant windowEnd;

    /** 所需观测分钟数，取值 1 至窗口分钟数（含边界）。 */
    private int durationMinutes;

    /** 申请状态。 */
    private RequestStatus status;

    /** 申请时间，UTC，候补排序主关键字（升序）。 */
    private Instant requestedAt;

    /** 锁定窗口时间（批准或候补接替），UTC；未分配为 null。 */
    private Instant allocatedAt;

    /** 撤销时间，UTC；未撤销为 null。 */
    private Instant cancelledAt;

    /** 候补接替来源：被撤销申请的编号；直接批准或候补中为 null。 */
    private String promotedFromRequestNo;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestNo() {
        return requestNo;
    }

    public void setRequestNo(String requestNo) {
        this.requestNo = requestNo;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getInstrumentId() {
        return instrumentId;
    }

    public void setInstrumentId(String instrumentId) {
        this.instrumentId = instrumentId;
    }

    public String getResearchGroup() {
        return researchGroup;
    }

    public void setResearchGroup(String researchGroup) {
        this.researchGroup = researchGroup;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(Instant windowStart) {
        this.windowStart = windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(Instant windowEnd) {
        this.windowEnd = windowEnd;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public void setStatus(RequestStatus status) {
        this.status = status;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public Instant getAllocatedAt() {
        return allocatedAt;
    }

    public void setAllocatedAt(Instant allocatedAt) {
        this.allocatedAt = allocatedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public String getPromotedFromRequestNo() {
        return promotedFromRequestNo;
    }

    public void setPromotedFromRequestNo(String promotedFromRequestNo) {
        this.promotedFromRequestNo = promotedFromRequestNo;
    }
}
