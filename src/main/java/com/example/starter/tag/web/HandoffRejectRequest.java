package com.example.starter.tag.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 接收站点拒绝交接请求。拒绝后交接单关闭并记录原因，持有权不转移。
 */
public class HandoffRejectRequest {

    @NotBlank
    private String handoffCode;

    @NotBlank
    private String siteCode;

    @NotBlank
    @Size(max = 200)
    private String reason;

    @NotNull
    private Instant at;

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

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }
}
