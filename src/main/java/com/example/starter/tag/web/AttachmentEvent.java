package com.example.starter.tag.web;

import java.time.Instant;

/**
 * 时间线中的一段附着区间（左闭右开）。
 */
public class AttachmentEvent {

    private String tagCode;
    private String animalCode;
    private String siteCode;
    private Instant attachAt;
    private Instant detachAt;
    private String detachSiteCode;

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

    public Instant getDetachAt() {
        return detachAt;
    }

    public void setDetachAt(Instant detachAt) {
        this.detachAt = detachAt;
    }

    public String getDetachSiteCode() {
        return detachSiteCode;
    }

    public void setDetachSiteCode(String detachSiteCode) {
        this.detachSiteCode = detachSiteCode;
    }
}
