package com.example.starter.web.dto;

/**
 * 单个维度的额度视图。
 */
public class QuotaView {

    /** 额度维度：CAMPAIGN=公告当日总额度，VISITOR=公告内访客当日额度。 */
    private String scope;
    private String campaignId;
    /** 仅 VISITOR 维度返回。 */
    private String visitorId;
    /** UTC 日，yyyy-MM-dd。 */
    private String utcDate;
    /** 当日上限，单位：次。 */
    private int cap;
    /** 当前占用（RESERVED + CONFIRMED），单位：次。 */
    private int held;
    /** 剩余可用，单位：次。 */
    private int available;

    public QuotaView() {
    }

    public QuotaView(String scope, String campaignId, String visitorId, String utcDate,
                     int cap, int held) {
        this.scope = scope;
        this.campaignId = campaignId;
        this.visitorId = visitorId;
        this.utcDate = utcDate;
        this.cap = cap;
        this.held = held;
        this.available = cap - held;
    }

    public String getScope() {
        return scope;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public String getVisitorId() {
        return visitorId;
    }

    public String getUtcDate() {
        return utcDate;
    }

    public int getCap() {
        return cap;
    }

    public int getHeld() {
        return held;
    }

    public int getAvailable() {
        return available;
    }
}
