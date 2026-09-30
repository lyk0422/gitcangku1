package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.example.starter.calibration.api.dto.EnvironmentItemFailure;

/**
 * 环境补偿放行被拒绝（HTTP 422）：温湿度越出证书范围、系数未生效或同时命中多个版本。
 * 整批不生成任何放行记录，响应携带各条实际温湿度读数与原因码。
 */
public class EnvironmentRejectedException extends ApiException {

    private final List<EnvironmentItemFailure> failures;

    public EnvironmentRejectedException(List<EnvironmentItemFailure> failures) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "ENVIRONMENT_RELEASE_REJECTED",
                "环境补偿放行被拒绝：温湿度越界或系数版本不满足要求");
        this.failures = List.copyOf(failures);
    }

    public List<EnvironmentItemFailure> getFailures() {
        return failures;
    }
}
