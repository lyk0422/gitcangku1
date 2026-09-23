package com.example.starter.spectrum.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 一次频率方案提交：1~20 个不重复台站的新频道或静默值 0。
 * 未列出的台站保持原状态；携带 expectedVersion 与全局唯一 planKey。
 */
public class SubmitPlanRequest {

    /** 客户端期望的当前网络版本；与库内不一致返回 409。 */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /** 全局唯一方案键；同键重放不增加版本，换请求键复用该键返回 409。 */
    @NotBlank
    @Size(max = 96)
    private String planKey;

    /** 本次变更台站集合，1~20 个且台站不重复；频道 0 静默，1~8 发射。 */
    @NotEmpty
    @Size(min = 1, max = 20)
    @Valid
    private List<ChannelChange> changes;

    public Integer getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(Integer expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public String getPlanKey() {
        return planKey;
    }

    public void setPlanKey(String planKey) {
        this.planKey = planKey;
    }

    public List<ChannelChange> getChanges() {
        return changes;
    }

    public void setChanges(List<ChannelChange> changes) {
        this.changes = changes;
    }

    /** 单个台站的目标频道设置。 */
    public static class ChannelChange {

        /** 目标台站ID，必须属于该网络。 */
        @NotBlank
        @Size(max = 64)
        private String stationId;

        /** 新频道，0 表示静默，1~8 为发射频道。 */
        @NotNull
        @Min(0)
        @Max(8)
        private Integer channel;

        public String getStationId() {
            return stationId;
        }

        public void setStationId(String stationId) {
            this.stationId = stationId;
        }

        public Integer getChannel() {
            return channel;
        }

        public void setChannel(Integer channel) {
            this.channel = channel;
        }
    }
}
