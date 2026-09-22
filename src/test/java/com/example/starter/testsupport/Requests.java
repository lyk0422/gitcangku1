package com.example.starter.testsupport;

import com.example.starter.web.dto.ApplyExposureRequest;
import com.example.starter.web.dto.CreateCampaignRequest;
import com.example.starter.web.dto.ReservationActionRequest;

/**
 * 测试请求构造工具。
 */
public final class Requests {

    private Requests() {
    }

    public static CreateCampaignRequest createCampaign(String requestId, String campaignId,
                                                       int dailyTotalCap, int visitorCap) {
        CreateCampaignRequest r = new CreateCampaignRequest();
        r.setRequestId(requestId);
        r.setCampaignId(campaignId);
        r.setDailyTotalCap(dailyTotalCap);
        r.setVisitorCap(visitorCap);
        return r;
    }

    public static ApplyExposureRequest apply(String requestId, String campaignId, String visitorId) {
        ApplyExposureRequest r = new ApplyExposureRequest();
        r.setRequestId(requestId);
        r.setCampaignId(campaignId);
        r.setVisitorId(visitorId);
        return r;
    }

    public static ReservationActionRequest action(String requestId) {
        ReservationActionRequest r = new ReservationActionRequest();
        r.setRequestId(requestId);
        return r;
    }
}
