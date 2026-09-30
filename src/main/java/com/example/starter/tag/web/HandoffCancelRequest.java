package com.example.starter.tag.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 发起站点取消尚未确认的交接。已确认的交接不得取消。
 */
public class HandoffCancelRequest {

    @NotBlank
    private String handoffCode;

    @NotBlank
    private String siteCode;

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

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }
}
