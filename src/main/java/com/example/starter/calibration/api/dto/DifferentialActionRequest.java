package com.example.starter.calibration.api.dto;

/**
 * 差分放行/取消请求体。expectedVersion 为可选的期望版本号，
 * 提供时与当前版本不一致则拒绝（409），并计入幂等身份指纹。
 *
 * @param expectedVersion 期望的差分记录版本号，可为 null 表示不校验
 */
public record DifferentialActionRequest(Long expectedVersion) {
}
