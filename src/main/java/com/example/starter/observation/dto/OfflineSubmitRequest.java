package com.example.starter.observation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 离线提交请求：携带 baseVersion 及三个字段的完整候选值。
 */
public class OfflineSubmitRequest {

    @NotNull
    @Positive
    private Integer baseVersion;

    @NotNull
    @Size(max = 512)
    private String location;

    @NotNull
    @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "读数必须是最多三位小数的十进制字符串")
    private String reading;

    @NotNull
    @Size(max = 2048)
    private String remark;

    public Integer getBaseVersion() {
        return baseVersion;
    }

    public void setBaseVersion(Integer baseVersion) {
        this.baseVersion = baseVersion;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getReading() {
        return reading;
    }

    public void setReading(String reading) {
        this.reading = reading;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
