package com.example.starter.observation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建观测记录请求，同时给出三个可编辑字段的初始值，版本从 1 开始。
 */
public class CreateRequest {

    @NotBlank
    @Size(max = 512)
    private String observationId;

    @NotBlank
    @Size(max = 512)
    private String location;

    @NotBlank
    @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "读数必须是最多三位小数的十进制字符串")
    private String reading;

    @NotBlank
    @Size(max = 2048)
    private String remark;

    public String getObservationId() {
        return observationId;
    }

    public void setObservationId(String observationId) {
        this.observationId = observationId;
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
