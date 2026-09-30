package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 放行环境校验失败：温湿度越出证书范围、放行时刻无生效系数版本或同时命中多个版本。
 * 返回 422，不生成任何放行记录；错误体携带实际读数与命中版本等现场值。
 */
public class ReleaseEnvironmentException extends ApiException {

    private final transient Object details;

    public ReleaseEnvironmentException(String code, String message, Object details) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
        this.details = details;
    }

    public Object getDetails() {
        return details;
    }

    /**
     * 422 错误详情。
     *
     * @param temperature       实际放行环境温度（℃）
     * @param humidity          实际放行环境湿度（%RH）
     * @param matchedVersions   放行时刻命中的系数版本标签；无命中为空列表，多命中为多个
     * @param failures          越界项列表（键 + 原因码）
     */
    public record Details(String temperature, String humidity, List<String> matchedVersions,
                          List<ItemFailure> failures) {
    }
}
