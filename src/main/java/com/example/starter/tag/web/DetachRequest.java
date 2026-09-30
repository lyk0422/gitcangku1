package com.example.starter.tag.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 标签摘除请求。摘除时刻必须晚于该标签当前附着区间的起始时刻。
 */
public class DetachRequest {

    @NotBlank
    private String tagCode;

    @NotBlank
    private String siteCode;

    @NotNull
    private Instant detachAt;

    public String getTagCode() {
        return tagCode;
    }

    public void setTagCode(String tagCode) {
        this.tagCode = tagCode;
    }

    public String getSiteCode() {
        return siteCode;
    }

    public void setSiteCode(String siteCode) {
        this.siteCode = siteCode;
    }

    public Instant getDetachAt() {
        return detachAt;
    }

    public void setDetachAt(Instant detachAt) {
        this.detachAt = detachAt;
    }
}
