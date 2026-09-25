package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 发布计划请求。requestKey 为幂等键，指纹含操作者；
 * driver/conductor 同时缺省时按无乘务发布，二者必须同时提供且为不同人员。
 */
public record PublishPlanRequest(
        @NotBlank String requestKey,
        String operator,
        @Valid CrewAssignmentRequest driver,
        @Valid CrewAssignmentRequest conductor) {

    /** 兼容旧调用：仅幂等键。 */
    public PublishPlanRequest(String requestKey) {
        this(requestKey, null, null, null);
    }
}
