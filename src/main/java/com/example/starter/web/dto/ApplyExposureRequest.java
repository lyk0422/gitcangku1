package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 曝光申请请求；成功返回 60 秒有效的预占。
 */
public class ApplyExposureRequest {

    /** 客户端提供的全局唯一请求编号，用于写操作幂等。 */
    @NotBlank
    @Size(max = 64)
    private String requestId;

    /** 所属公告编号。 */
    @NotBlank
    @Size(max = 64)
    private String campaignId;

    /** 合成访客编号。 */
    @NotBlank
    @Size(max = 64)
    private String visitorId;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(String campaignId) {
        this.campaignId = campaignId;
    }

    public String getVisitorId() {
        return visitorId;
    }

    public void setVisitorId(String visitorId) {
        this.visitorId = visitorId;
    }
}
