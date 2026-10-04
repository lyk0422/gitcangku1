package com.example.starter.api.dto;

/**
 * 互斥规则写操作结果。
 *
 * @param active            操作后该互斥对是否生效：新增=true，撤销=false
 * @param repositoryVersion 该次写操作完成后的仓库版本号
 */
public record CapabilityExclusionResponse(
        String capabilityA,
        String capabilityB,
        boolean active,
        long repositoryVersion) {
}