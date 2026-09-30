package com.example.starter.tag.web;

import java.util.List;

/**
 * 动物完整时间线：按时间排序的全部标签附着区间。
 */
public class AnimalTimelineResponse {

    private String animalCode;
    private List<AttachmentEvent> attachments;

    public String getAnimalCode() {
        return animalCode;
    }

    public void setAnimalCode(String animalCode) {
        this.animalCode = animalCode;
    }

    public List<AttachmentEvent> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<AttachmentEvent> attachments) {
        this.attachments = attachments;
    }
}
