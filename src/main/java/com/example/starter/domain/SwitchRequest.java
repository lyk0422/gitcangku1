package com.example.starter.domain;

import java.time.Instant;

/**
 * 注入/采出切换申请与审核决定。
 */
public class SwitchRequest {

    /** 切换申请主键。 */
    private Long id;
    /** 井号。 */
    private String wellNo;
    /** 申请时进行中的运行段主键。 */
    private Long fromSegmentId;
    /** 目标模式：INJECTION 注入，PRODUCTION 采出。 */
    private RunMode targetMode;
    /** 状态：PENDING 待审核，APPROVED 已批准，REJECTED 已拒绝。 */
    private String status;
    /** 拒绝原因；NULL 表示未拒绝。 */
    private String rejectReason;
    /** 批准后生成的新运行段主键；NULL 表示未批准。 */
    private Long newSegmentId;
    /** 批准后冻结的新模式起点（UTC）；NULL 表示未批准。 */
    private Instant newModeStart;
    /** 决定类型：APPROVED 批准，OPERATOR_REJECTED 审核人拒绝，CONDITION_FAILED 条件不满足被拒；NULL 表示未决定。 */
    private String decisionOutcome;
    /** 申请的幂等请求编号。 */
    private String requestId;
    /** 审核决定的幂等请求编号；NULL 表示未审核。 */
    private String decisionRequestId;
    /** 申请时刻（UTC）。 */
    private Instant createdAt;
    /** 审核决定时刻（UTC）；NULL 表示未审核。 */
    private Instant decidedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getWellNo() {
        return wellNo;
    }

    public void setWellNo(String wellNo) {
        this.wellNo = wellNo;
    }

    public Long getFromSegmentId() {
        return fromSegmentId;
    }

    public void setFromSegmentId(Long fromSegmentId) {
        this.fromSegmentId = fromSegmentId;
    }

    public RunMode getTargetMode() {
        return targetMode;
    }

    public void setTargetMode(RunMode targetMode) {
        this.targetMode = targetMode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public Long getNewSegmentId() {
        return newSegmentId;
    }

    public void setNewSegmentId(Long newSegmentId) {
        this.newSegmentId = newSegmentId;
    }

    public Instant getNewModeStart() {
        return newModeStart;
    }

    public void setNewModeStart(Instant newModeStart) {
        this.newModeStart = newModeStart;
    }

    public String getDecisionOutcome() {
        return decisionOutcome;
    }

    public void setDecisionOutcome(String decisionOutcome) {
        this.decisionOutcome = decisionOutcome;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getDecisionRequestId() {
        return decisionRequestId;
    }

    public void setDecisionRequestId(String decisionRequestId) {
        this.decisionRequestId = decisionRequestId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }
}