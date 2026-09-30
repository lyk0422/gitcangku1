package com.example.starter.tag.web;

import java.util.List;

/**
 * 标签完整时间线：附着历史与交接历史按时间排序。
 */
public class TagTimelineResponse {

    private String tagCode;
    private String holderSiteCode;
    private List<AttachmentEvent> attachments;
    private List<HandoffEvent> handoffs;

    public String getTagCode() {
        return tagCode;
    }

    public void setTagCode(String tagCode) {
        this.tagCode = tagCode;
    }

    public String getHolderSiteCode() {
        return holderSiteCode;
    }

    public void setHolderSiteCode(String holderSiteCode) {
        this.holderSiteCode = holderSiteCode;
    }

    public List<AttachmentEvent> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<AttachmentEvent> attachments) {
        this.attachments = attachments;
    }

    public List<HandoffEvent> getHandoffs() {
        return handoffs;
    }

    public void setHandoffs(List<HandoffEvent> handoffs) {
        this.handoffs = handoffs;
    }
}
