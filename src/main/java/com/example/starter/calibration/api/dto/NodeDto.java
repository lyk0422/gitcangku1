package com.example.starter.calibration.api.dto;

/**
 * 曲线证书节点（请求）。raw 与 corrected 均为最多 6 位小数的十进制字符串。
 *
 * @param raw       节点原始值，十进制字符串
 * @param corrected 节点校正值，十进制字符串
 */
public record NodeDto(String raw, String corrected) {
}
