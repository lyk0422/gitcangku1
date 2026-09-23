package com.example.starter.consent.migration.dto;

/**
 * 查询代次签发响应：一个查询代次固定一个目录代次，不允许混读旧新用途。
 *
 * @param queryGeneration   查询代次号，批量查询必须携带
 * @param catalogGeneration 该查询代次固定的目录代次
 * @param status            签发时状态：ACTIVE；目录迁移生效后旧查询代次被拒绝
 */
public record QueryGenerationResponse(long queryGeneration, long catalogGeneration, String status) {
}
