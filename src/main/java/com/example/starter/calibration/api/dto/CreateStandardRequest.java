package com.example.starter.calibration.api.dto;

/**
 * 创建标准器请求。
 *
 * @param standardId 标准器业务 ID，全局唯一
 * @param name       标准器名称
 */
public record CreateStandardRequest(
        String standardId,
        String name) {
}
