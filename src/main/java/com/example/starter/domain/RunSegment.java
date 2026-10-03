package com.example.starter.domain;

import java.time.Instant;

/**
 * 地热井运行段。
 */
public class RunSegment {

    /** 运行段主键。 */
    private Long id;
    /** 井号。 */
    private String wellNo;
    /** 运行模式：INJECTION 注入，PRODUCTION 采出。 */
    private RunMode mode;
    /** 段开始时刻（UTC）；新段不得早于旧段结束时刻。 */
    private Instant startTime;
    /** 段结束时刻（UTC）；NULL 表示进行中。 */
    private Instant endTime;
    /** 创建该段的幂等请求编号（切换审核生成时为 switch:<申请id>）。 */
    private String requestId;

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

    public RunMode getMode() {
        return mode;
    }

    public void setMode(RunMode mode) {
        this.mode = mode;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}