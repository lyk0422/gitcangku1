package com.example.starter.web.dto;

/**
 * 公告响应。
 */
public class CampaignResponse {

    private String campaignId;
    private int dailyTotalCap;
    private int visitorCap;
    /** 创建时刻，UTC 毫秒时间戳。 */
    private long createdAt;

    public CampaignResponse() {
    }

    public CampaignResponse(String campaignId, int dailyTotalCap, int visitorCap, long createdAt) {
        this.campaignId = campaignId;
        this.dailyTotalCap = dailyTotalCap;
        this.visitorCap = visitorCap;
        this.createdAt = createdAt;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public int getDailyTotalCap() {
        return dailyTotalCap;
    }

    public int getVisitorCap() {
        return visitorCap;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
