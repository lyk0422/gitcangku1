package com.example.starter.domain;

/**
 * 公告持久化对象；额度单位均为“次”，创建后固定。
 */
public class Campaign {

    private long id;
    private String campaignId;
    private int dailyTotalCap;
    private int visitorCap;
    /** 创建时刻，UTC 毫秒时间戳。 */
    private long createdAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(String campaignId) {
        this.campaignId = campaignId;
    }

    public int getDailyTotalCap() {
        return dailyTotalCap;
    }

    public void setDailyTotalCap(int dailyTotalCap) {
        this.dailyTotalCap = dailyTotalCap;
    }

    public int getVisitorCap() {
        return visitorCap;
    }

    public void setVisitorCap(int visitorCap) {
        this.visitorCap = visitorCap;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
