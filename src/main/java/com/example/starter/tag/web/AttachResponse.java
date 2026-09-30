package com.example.starter.tag.web;

import java.time.Instant;

/**
 * 附着结果。{@code replay=true} 表示该响应来自同参 requestId 重放。
 */
public class AttachResponse {

    private Long attachmentId;
    private String tagCode;
    private String animalCode;
    private String siteCode;
    private Instant attachAt;
    private boolean replay;

    public AttachResponse() {
    }

    public AttachResponse(Long attachmentId, String tagCode, String animalCode, String siteCode,
                          Instant attachAt, boolean replay) {
        this.attachmentId = attachmentId;
        this.tagCode = tagCode;
        this.animalCode = animalCode;
        this.siteCode = siteCode;
        this.attachAt = attachAt;
        this.replay = replay;
    }

    public Long getAttachmentId() {
        return attachmentId;
    }

    public void setAttachmentId(Long attachmentId) {
        this.attachmentId = attachmentId;
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

    public boolean isReplay() {
        return replay;
    }

    public void setReplay(boolean replay) {
        this.replay = replay;
    }
}
