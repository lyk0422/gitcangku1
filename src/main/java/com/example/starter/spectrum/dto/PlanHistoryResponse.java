package com.example.starter.spectrum.dto;

import java.util.List;
import java.util.Map;

/**
 * 不可变方案历史查询结果，按版本升序返回。
 */
public class PlanHistoryResponse {

    /** 网络ID。 */
    private String networkId;

    /** 不可变方案记录列表。 */
    private List<PlanRecord> plans;

    public String getNetworkId() {
        return networkId;
    }

    public void setNetworkId(String networkId) {
        this.networkId = networkId;
    }

    public List<PlanRecord> getPlans() {
        return plans;
    }

    public void setPlans(List<PlanRecord> plans) {
        this.plans = plans;
    }

    /** 单条不可变方案历史记录。 */
    public static class PlanRecord {

        /** 方案键。 */
        private String planKey;

        /** 首次成功提交携带的请求ID。 */
        private String requestId;

        /** 方案生效后的网络版本。 */
        private int version;

        /** 提交前台站ID到频道（0 静默）的完整映射。 */
        private Map<String, Integer> channelsBefore;

        /** 提交后完整网络频道映射。 */
        private Map<String, Integer> channelsAfter;

        /** 提交后各发射接收台站同频道累计干扰汇总。 */
        private List<PlanResultResponse.InterferenceItem> interferenceSummary;

        public String getPlanKey() {
            return planKey;
        }

        public void setPlanKey(String planKey) {
            this.planKey = planKey;
        }

        public String getRequestId() {
            return requestId;
        }

        public void setRequestId(String requestId) {
            this.requestId = requestId;
        }

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }

        public Map<String, Integer> getChannelsBefore() {
            return channelsBefore;
        }

        public void setChannelsBefore(Map<String, Integer> channelsBefore) {
            this.channelsBefore = channelsBefore;
        }

        public Map<String, Integer> getChannelsAfter() {
            return channelsAfter;
        }

        public void setChannelsAfter(Map<String, Integer> channelsAfter) {
            this.channelsAfter = channelsAfter;
        }

        public List<PlanResultResponse.InterferenceItem> getInterferenceSummary() {
            return interferenceSummary;
        }

        public void setInterferenceSummary(List<PlanResultResponse.InterferenceItem> interferenceSummary) {
            this.interferenceSummary = interferenceSummary;
        }
    }
}
