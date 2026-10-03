package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 井口压力采样（只追加，错误读数以作废标记加替代读数纠正）。
 */
public class PressureSample {

    /** 采样主键。 */
    private Long id;
    /** 井号。 */
    private String wellNo;
    /** 所属运行段主键。 */
    private Long segmentId;
    /** 井口压力读数，单位 MPa。 */
    private BigDecimal pressureMpa;
    /** 采样时刻（UTC）。 */
    private Instant sampledAt;
    /** 采样的幂等请求编号；替代读数为 <作废请求编号>#replacement。 */
    private String requestId;
    /** 是否已作废；作废不删除行，仅追加标记。 */
    private boolean voided;
    /** 作废原因；NULL 表示未作废。 */
    private String voidReason;
    /** 作废时刻（UTC）；NULL 表示未作废。 */
    private Instant voidedAt;
    /** 替代读数的采样主键；NULL 表示无替代。 */
    private Long replacedById;

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

    public Long getSegmentId() {
        return segmentId;
    }

    public void setSegmentId(Long segmentId) {
        this.segmentId = segmentId;
    }

    public BigDecimal getPressureMpa() {
        return pressureMpa;
    }

    public void setPressureMpa(BigDecimal pressureMpa) {
        this.pressureMpa = pressureMpa;
    }

    public Instant getSampledAt() {
        return sampledAt;
    }

    public void setSampledAt(Instant sampledAt) {
        this.sampledAt = sampledAt;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public boolean isVoided() {
        return voided;
    }

    public void setVoided(boolean voided) {
        this.voided = voided;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public void setVoidReason(String voidReason) {
        this.voidReason = voidReason;
    }

    public Instant getVoidedAt() {
        return voidedAt;
    }

    public void setVoidedAt(Instant voidedAt) {
        this.voidedAt = voidedAt;
    }

    public Long getReplacedById() {
        return replacedById;
    }

    public void setReplacedById(Long replacedById) {
        this.replacedById = replacedById;
    }
}