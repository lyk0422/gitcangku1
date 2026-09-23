package com.example.starter.spectrum.dto;

import java.util.List;
import java.util.Map;

/**
 * 方案提交成功结果：原子替换后的完整前后配置与提交后干扰汇总。
 */
public class PlanResultResponse {

    /** 本次生效的方案键。 */
    private String planKey;

    /** 提交前网络版本。 */
    private int versionBefore;

    /** 提交后网络版本（等于 versionBefore + 1）。 */
    private int versionAfter;

    /** 提交前台站ID到频道（0 静默）的完整映射。 */
    private Map<String, Integer> channelsBefore;

    /** 提交后完整网络频道映射。 */
    private Map<String, Integer> channelsAfter;

    /** 提交后各正在发射的接收台站同频道累计干扰，按台站ID排序。 */
    private List<InterferenceItem> interferenceSummary;

    public String getPlanKey() {
        return planKey;
    }

    public void setPlanKey(String planKey) {
        this.planKey = planKey;
    }

    public int getVersionBefore() {
        return versionBefore;
    }

    public void setVersionBefore(int versionBefore) {
        this.versionBefore = versionBefore;
    }

    public int getVersionAfter() {
        return versionAfter;
    }

    public void setVersionAfter(int versionAfter) {
        this.versionAfter = versionAfter;
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

    public List<InterferenceItem> getInterferenceSummary() {
        return interferenceSummary;
    }

    public void setInterferenceSummary(List<InterferenceItem> interferenceSummary) {
        this.interferenceSummary = interferenceSummary;
    }

    /** 单个接收台站的累计干扰结果。 */
    public static class InterferenceItem {

        /** 正在发射的接收台站ID。 */
        private String stationId;

        /** 所有同频道其他发射台站指向该台站的干扰量累计值；静默台站不发射。 */
        private int accumulated;

        /** 该台站干扰预算；accumulated 恰好等于预算合法。 */
        private int budget;

        public InterferenceItem() {
        }

        public InterferenceItem(String stationId, int accumulated, int budget) {
            this.stationId = stationId;
            this.accumulated = accumulated;
            this.budget = budget;
        }

        public String getStationId() {
            return stationId;
        }

        public void setStationId(String stationId) {
            this.stationId = stationId;
        }

        public int getAccumulated() {
            return accumulated;
        }

        public void setAccumulated(int accumulated) {
            this.accumulated = accumulated;
        }

        public int getBudget() {
            return budget;
        }

        public void setBudget(int budget) {
            this.budget = budget;
        }
    }
}
