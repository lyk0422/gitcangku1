package com.example.starter.web.dto;

import java.util.List;

/**
 * 额度查询响应：公告维度必含，访客维度在指定 visitorId 时返回。
 */
public class QuotaResponse {

    private QuotaView campaignQuota;
    private List<QuotaView> visitorQuotas;

    public QuotaResponse() {
    }

    public QuotaResponse(QuotaView campaignQuota, List<QuotaView> visitorQuotas) {
        this.campaignQuota = campaignQuota;
        this.visitorQuotas = visitorQuotas;
    }

    public QuotaView getCampaignQuota() {
        return campaignQuota;
    }

    public void setCampaignQuota(QuotaView campaignQuota) {
        this.campaignQuota = campaignQuota;
    }

    public List<QuotaView> getVisitorQuotas() {
        return visitorQuotas;
    }

    public void setVisitorQuotas(List<QuotaView> visitorQuotas) {
        this.visitorQuotas = visitorQuotas;
    }
}
