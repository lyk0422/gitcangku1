package com.example.starter.tag.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

/**
 * 标签附着请求。时间以 UTC ISO-8601 时刻提交。
 */
public class AttachRequest {

    @NotBlank
    private String requestId;

    @NotBlank
    private String tagCode;

    @NotBlank
    private String animalCode;

    @NotBlank
    private String siteCode;

    @NotNull
    private Instant attachAt;

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

    public String getAnimalCode() {
        return animalCode;
    }

    public void setAnimalCode(String animalCode) {
        this.animalCode = animalCode;
    }

    public String getSiteCode() {
        return siteCode;
    }

    public void setSiteCode(String siteCode) {
        this.siteCode = siteCode;
    }

    public Instant getAttachAt() {
        return attachAt;
    }

    public void setAttachAt(Instant attachAt) {
        this.attachAt = attachAt;
    }
}
