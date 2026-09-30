package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 靠泊申请及其决策快照。
 *
 * <p>原决策字段（tideVersionId 起至 clearanceM）在申请首次校核时写入，之后即使潮高修订也不改写，
 * 用于保留已开始靠泊的决策依据；revision* 字段记录最近一次版本修订/复核的校核结果。</p>
 */
public class Allocation {

    private Long id;

    /** 幂等键，同一 requestId 重复申请必须命中同一条记录。 */
    private String requestId;

    /** 合成船名，仅用于展示。 */
    private String vesselName;

    /** 船舶吃水，单位米。 */
    private BigDecimal draftM;

    /** 安全余量要求，单位米。 */
    private BigDecimal safetyMarginM;

    private Long berthId;

    /** 靠泊起始时间（含），业务时区 Asia/Shanghai。 */
    private LocalDateTime startTime;

    /** 靠泊结束时间（不含）。 */
    private LocalDateTime endTime;

    private AllocationStatus status;

    /** 首次校核所用潮高版本 id；申请被潮高缺口拒绝时为空。 */
    private Long tideVersionId;

    /** 首次校核时整个申请区间内的最低有效潮高，单位米。 */
    private BigDecimal minTideHeightM;

    /** 泊位静态水深快照，单位米。 */
    private BigDecimal staticDepthM;

    /** 首次校核可用深度 = 静态水深 + 最低潮高，单位米。 */
    private BigDecimal availableDepthM;

    /** 需要深度 = 吃水 + 安全余量，单位米。 */
    private BigDecimal requiredDepthM;

    /** 富余余量 = 可用深度 - 需要深度，单位米，负值表示不足。 */
    private BigDecimal clearanceM;

    /** 最近一次修订/复核所依据的潮高版本 id，未发生时为空。 */
    private Long reviewTideVersionId;

    /** 修订/复核时区间最低潮高，单位米。 */
    private BigDecimal reviewMinTideHeightM;

    /** 修订/复核时可用深度，单位米。 */
    private BigDecimal reviewAvailableDepthM;

    /** 修订/复核时富余余量，单位米。 */
    private BigDecimal reviewClearanceM;

    /** 拒绝或挂起原因代码，见 {@link RejectReason}。 */
    private String rejectReason;

    /** 已执行的复核次数。 */
    private Integer reviewCount;

    /** 最近一次决策时间（确认/拒绝/挂起/复核）。 */
    private LocalDateTime decidedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getVesselName() {
        return vesselName;
    }

    public void setVesselName(String vesselName) {
        this.vesselName = vesselName;
    }

    public BigDecimal getDraftM() {
        return draftM;
    }

    public void setDraftM(BigDecimal draftM) {
        this.draftM = draftM;
    }

    public BigDecimal getSafetyMarginM() {
        return safetyMarginM;
    }

    public void setSafetyMarginM(BigDecimal safetyMarginM) {
        this.safetyMarginM = safetyMarginM;
    }

    public Long getBerthId() {
        return berthId;
    }

    public void setBerthId(Long berthId) {
        this.berthId = berthId;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public AllocationStatus getStatus() {
        return status;
    }

    public void setStatus(AllocationStatus status) {
        this.status = status;
    }

    public Long getTideVersionId() {
        return tideVersionId;
    }

    public void setTideVersionId(Long tideVersionId) {
        this.tideVersionId = tideVersionId;
    }

    public BigDecimal getMinTideHeightM() {
        return minTideHeightM;
    }

    public void setMinTideHeightM(BigDecimal minTideHeightM) {
        this.minTideHeightM = minTideHeightM;
    }

    public BigDecimal getStaticDepthM() {
        return staticDepthM;
    }

    public void setStaticDepthM(BigDecimal staticDepthM) {
        this.staticDepthM = staticDepthM;
    }

    public BigDecimal getAvailableDepthM() {
        return availableDepthM;
    }

    public void setAvailableDepthM(BigDecimal availableDepthM) {
        this.availableDepthM = availableDepthM;
    }

    public BigDecimal getRequiredDepthM() {
        return requiredDepthM;
    }

    public void setRequiredDepthM(BigDecimal requiredDepthM) {
        this.requiredDepthM = requiredDepthM;
    }

    public BigDecimal getClearanceM() {
        return clearanceM;
    }

    public void setClearanceM(BigDecimal clearanceM) {
        this.clearanceM = clearanceM;
    }

    public Long getReviewTideVersionId() {
        return reviewTideVersionId;
    }

    public void setReviewTideVersionId(Long reviewTideVersionId) {
        this.reviewTideVersionId = reviewTideVersionId;
    }

    public BigDecimal getReviewMinTideHeightM() {
        return reviewMinTideHeightM;
    }

    public void setReviewMinTideHeightM(BigDecimal reviewMinTideHeightM) {
        this.reviewMinTideHeightM = reviewMinTideHeightM;
    }

    public BigDecimal getReviewAvailableDepthM() {
        return reviewAvailableDepthM;
    }

    public void setReviewAvailableDepthM(BigDecimal reviewAvailableDepthM) {
        this.reviewAvailableDepthM = reviewAvailableDepthM;
    }

    public BigDecimal getReviewClearanceM() {
        return reviewClearanceM;
    }

    public void setReviewClearanceM(BigDecimal reviewClearanceM) {
        this.reviewClearanceM = reviewClearanceM;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public Integer getReviewCount() {
        return reviewCount;
    }

    public void setReviewCount(Integer reviewCount) {
        this.reviewCount = reviewCount;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
