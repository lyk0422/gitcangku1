package com.example.starter.calibration.api.dto;

/**
 * 曲线证书节点（响应）。
 *
 * @param raw       节点原始值（十进制字符串）
 * @param corrected 节点校正值（十进制字符串）
 */
public record NodeResponse(String raw, String corrected) {
}
