package com.example.starter.pass.persist;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 卫星过境预测的一个不可变修订版本。
 *
 * <p>同一卫星每次提交/修订预测新增一行，{@code revision_no} 从 1 递增；
 * 被更新版本取代后 {@code superseded=true}。已完成接收的时隙永久保留其确认时的版本证据。</p>
 */
public class PassPredictionPo {

    /** 自增主键。 */
    private Long id;

    /** 卫星业务编码。 */
    private String satelliteCode;

    /** 修订版本号，同一卫星从 1 起单调递增。 */
    private int revisionNo;

    /** UTC 可见窗口起点（含），时区固定 UTC。 */
    private Instant visibleStart;

    /** UTC 可见窗口终点（不含），半开区间。 */
    private Instant visibleEnd;

    /** 本次过境预测的最低仰角，单位：度。 */
    private BigDecimal minElevationDeg;

    /** 本次过境计划下传数据量，单位：字节。 */
    private long plannedBytes;

    /** 是否已被更新的修订版本取代。 */
    private boolean superseded;

    /** 版本落库时间，UTC 时刻。 */
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSatelliteCode() {
        return satelliteCode;
    }

    public void setSatelliteCode(String satelliteCode) {
        this.satelliteCode = satelliteCode;
    }

    public int getRevisionNo() {
        return revisionNo;
    }

    public void setRevisionNo(int revisionNo) {
        this.revisionNo = revisionNo;
    }

    public Instant getVisibleStart() {
        return visibleStart;
    }

    public void setVisibleStart(Instant visibleStart) {
        this.visibleStart = visibleStart;
    }

    public Instant getVisibleEnd() {
        return visibleEnd;
    }

    public void setVisibleEnd(Instant visibleEnd) {
        this.visibleEnd = visibleEnd;
    }

    public BigDecimal getMinElevationDeg() {
        return minElevationDeg;
    }

    public void setMinElevationDeg(BigDecimal minElevationDeg) {
        this.minElevationDeg = minElevationDeg;
    }

    public long getPlannedBytes() {
        return plannedBytes;
    }

    public void setPlannedBytes(long plannedBytes) {
        this.plannedBytes = plannedBytes;
    }

    public boolean isSuperseded() {
        return superseded;
    }

    public void setSuperseded(boolean superseded) {
        this.superseded = superseded;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
