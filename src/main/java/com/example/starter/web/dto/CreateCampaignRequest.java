package com.example.starter.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建公告请求；额度创建后固定。
 */
public class CreateCampaignRequest {

    /** 客户端提供的全局唯一请求编号，用于写操作幂等。 */
    @NotBlank
    @Size(max = 64)
    private String requestId;

    /** 公告业务唯一编号。 */
    @NotBlank
    @Size(max = 64)
    private String campaignId;

    /** 每 UTC 日总额度，1~100000 整数，单位：次。 */
    @NotNull
    @Min(1)
    @Max(100000)
    private Integer dailyTotalCap;

    /** 每访客每 UTC 日上限，1~100000 整数，单位：次。 */
    @NotNull
    @Min(1)
    @Max(100000)
    private Integer visitorCap;

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

    public Integer getDailyTotalCap() {
        return dailyTotalCap;
    }

    public void setDailyTotalCap(Integer dailyTotalCap) {
        this.dailyTotalCap = dailyTotalCap;
    }

    public Integer getVisitorCap() {
        return visitorCap;
    }

    public void setVisitorCap(Integer visitorCap) {
        this.visitorCap = visitorCap;
    }
}
