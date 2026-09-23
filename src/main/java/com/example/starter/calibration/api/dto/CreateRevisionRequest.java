package com.example.starter.calibration.api.dto;

/**
 * 创建后继修订请求。仅允许修改测量值及说明，证书与原始输入保留自被驳回的前驱测量。
 *
 * @param version 被驳回前驱测量的版本号
 * @param reading 新的测量值（原始读数），十进制字符串
 * @param note    测量说明；可为空
 */
public record CreateRevisionRequest(Integer version, String reading, String note) {
}
