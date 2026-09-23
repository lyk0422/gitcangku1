package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Campaign;

/**
 * 投放活动视图。
 */
public record CampaignView(long campaignId, String name, String status) {

    public static CampaignView of(Campaign campaign) {
        return new CampaignView(campaign.id(), campaign.name(), campaign.status().name());
    }
}
