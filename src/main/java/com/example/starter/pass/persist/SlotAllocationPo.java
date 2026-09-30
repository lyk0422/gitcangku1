package com.example.starter.pass.persist;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 接收时隙分配（接收计划）行：一次有效过境对应一个接收站、一份计划。
 *
 * <p>确认时固化预测版本与容量快照；所有时间列为 UTC 半开窗口。</p>
 */
public class SlotAllocationPo {

    /** 自增主键。 */
    private Long id;

    /** 分配申请的幂等 requestId，唯一；同参重放返回本行，异参拒绝。 */
    private String requestId;

    /** 卫星业务编码。 */
    private String satelliteCode;

    /** 接收站点主键。 */
    private long stationId;

    /** 接收站点编码（确认时快照）。 */
    private String stationCode;

    /** 占用的天线编码（确认时快照）。 */
    private String antennaCode;

    /** 确认时固化的预测版本主键；已完成接收后永久保留，不随修订改变。 */
    private Long predictionId;

    /** 确认时固化的预测修订版本号。 */
    private int predictionRevisionNo;

    /** 申请接收窗口起点（含），UTC。 */
    private Instant windowStart;

    /** 申请接收窗口终点（不含），UTC，半开区间。 */
    private Instant windowEnd;

    /** 确认时预测给出的过境最低仰角快照，单位：度。 */
    private BigDecimal passMinElevationDeg;

    /** 计划数据量快照，单位：字节；实际接收不得超过该值。 */
    private long plannedBytes;

    /** 确认时站点仰角门槛快照，单位：度。 */
    private BigDecimal stationMinElevationDeg;

    /** 确认时天线每分钟容量快照，单位：字节/分钟。 */
    private long stationBytesPerMinute;

    /** 状态：CONFIRMED / PENDING_REASSIGN / RECEIVED / CANCELLED。 */
    private String status;

    /** 待重新分配原因码（status=PENDING_REASSIGN 时非空，其余为空）。 */
    private String pendingReason;

    /** 实际接收字节数，单位：字节；仅 status=RECEIVED 非空，且不超过 planned_bytes。 */
    private Long actualBytes;

    /** 取消原因（status=CANCELLED 时非空），其余状态为空。 */
    private String cancelReason;

    /** 确认落定时间，UTC；未确认语义不存在（本行创建即确认）。 */
    private Instant confirmedAt;

    /** 接收完成时间，UTC；仅 RECEIVED 非空。 */
    private Instant receivedAt;

    /** 取消时间，UTC；仅 CANCELLED 非空。 */
    private Instant cancelledAt;

    /** 行创建时间，UTC。 */
    private Instant createdAt;

    /** 行最近更新时间，UTC。 */
    private Instant updatedAt;

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

    public String getSatelliteCode() {
        return satelliteCode;
    }

    public void setSatelliteCode(String satelliteCode) {
        this.satelliteCode = satelliteCode;
    }

    public long getStationId() {
        return stationId;
    }

    public void setStationId(long stationId) {
        this.stationId = stationId;
    }

    public String getStationCode() {
        return stationCode;
    }

    public void setStationCode(String stationCode) {
        this.stationCode = stationCode;
    }

    public String getAntennaCode() {
        return antennaCode;
    }

    public void setAntennaCode(String antennaCode) {
        this.antennaCode = antennaCode;
    }

    public Long getPredictionId() {
        return predictionId;
    }

    public void setPredictionId(Long predictionId) {
        this.predictionId = predictionId;
    }

    public int getPredictionRevisionNo() {
        return predictionRevisionNo;
    }

    public void setPredictionRevisionNo(int predictionRevisionNo) {
        this.predictionRevisionNo = predictionRevisionNo;
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

    public BigDecimal getPassMinElevationDeg() {
        return passMinElevationDeg;
    }

    public void setPassMinElevationDeg(BigDecimal passMinElevationDeg) {
        this.passMinElevationDeg = passMinElevationDeg;
    }

    public long getPlannedBytes() {
        return plannedBytes;
    }

    public void setPlannedBytes(long plannedBytes) {
        this.plannedBytes = plannedBytes;
    }

    public BigDecimal getStationMinElevationDeg() {
        return stationMinElevationDeg;
    }

    public void setStationMinElevationDeg(BigDecimal stationMinElevationDeg) {
        this.stationMinElevationDeg = stationMinElevationDeg;
    }

    public long getStationBytesPerMinute() {
        return stationBytesPerMinute;
    }

    public void setStationBytesPerMinute(long stationBytesPerMinute) {
        this.stationBytesPerMinute = stationBytesPerMinute;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPendingReason() {
        return pendingReason;
    }

    public void setPendingReason(String pendingReason) {
        this.pendingReason = pendingReason;
    }

    public Long getActualBytes() {
        return actualBytes;
    }

    public void setActualBytes(Long actualBytes) {
        this.actualBytes = actualBytes;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
