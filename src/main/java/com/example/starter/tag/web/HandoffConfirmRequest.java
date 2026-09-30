package com.example.starter.tag.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 接收站点确认交接请求。只有交接单上的目标站点可以确认；
 * 确认成功后标签持有权原子转移到目标站点。
 */
public class HandoffConfirmRequest {

    @NotBlank
    private String handoffCode;

    @NotBlank
    private String siteCode;

    @NotNull
    private Instant confirmAt;

    public String getHandoffCode() {
        return handoffCode;
    }

    public void setHandoffCode(String handoffCode) {
        this.handoffCode = handoffCode;
    }

    public String getSiteCode() {
        return siteCode;
    }

    public void setSiteCode(String siteCode) {
        this.siteCode = siteCode;
    }

    public Instant getConfirmAt() {
        return confirmAt;
    }

    public void setConfirmAt(Instant confirmAt) {
        this.confirmAt = confirmAt;
    }
}
