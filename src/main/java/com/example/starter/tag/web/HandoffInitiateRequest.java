package com.example.starter.tag.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 发起站点间标签交接请求。只有标签当前持有站点可以发起，
 * 且标签必须已摘除（不存在未结束的附着区间）。
 */
public class HandoffInitiateRequest {

    @NotBlank
    private String requestId;

    @NotBlank
    private String tagCode;

    @NotBlank
    private String fromSiteCode;

    @NotBlank
    private String toSiteCode;

    @NotNull
    private Instant initiateAt;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getTagCode() {
        return tagCode;
    }

    public void setTagCode(String tagCode) {
        this.tagCode = tagCode;
    }

    public String getFromSiteCode() {
        return fromSiteCode;
    }

    public void setFromSiteCode(String fromSiteCode) {
        this.fromSiteCode = fromSiteCode;
    }

    public String getToSiteCode() {
        return toSiteCode;
    }

    public void setToSiteCode(String toSiteCode) {
        this.toSiteCode = toSiteCode;
    }

    public Instant getInitiateAt() {
        return initiateAt;
    }

    public void setInitiateAt(Instant initiateAt) {
        this.initiateAt = initiateAt;
    }
}
