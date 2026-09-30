package com.example.starter.model;

import java.time.Instant;

/**
 * 撤销触发候补接替的流水。窗口均为 UTC 左闭右开。
 */
public class SuccessionEvent {

    private Long id;

    /** 仪器编号。 */
    private String instrumentId;

    /** 被撤销而让出空档的申请编号。 */
    private String vacatedRequestNo;

    /** 接替空档的候补申请编号。 */
    private String promotedRequestNo;

    /** 空档开始时刻（含），UTC。 */
    private Instant gapStart;

    /** 空档结束时刻（不含），UTC。 */
    private Instant gapEnd;

    /** 接替发生时间，UTC。 */
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getInstrumentId() {
        return instrumentId;
    }

    public void setInstrumentId(String instrumentId) {
        this.instrumentId = instrumentId;
    }

    public String getVacatedRequestNo() {
        return vacatedRequestNo;
    }

    public void setVacatedRequestNo(String vacatedRequestNo) {
        this.vacatedRequestNo = vacatedRequestNo;
    }

    public String getPromotedRequestNo() {
        return promotedRequestNo;
    }

    public void setPromotedRequestNo(String promotedRequestNo) {
        this.promotedRequestNo = promotedRequestNo;
    }

    public Instant getGapStart() {
        return gapStart;
    }

    public void setGapStart(Instant gapStart) {
        this.gapStart = gapStart;
    }

    public Instant getGapEnd() {
        return gapEnd;
    }

    public void setGapEnd(Instant gapEnd) {
        this.gapEnd = gapEnd;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
