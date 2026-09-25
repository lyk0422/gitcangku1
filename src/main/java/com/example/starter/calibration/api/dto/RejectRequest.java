package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 批量驳回请求：对待放行批次整批驳回修订，原子生效。
 *
 * @param keys    测量键列表（1～50，不可重复）
 * @param reason  驳回原因
 * @param calcKey 幂等键；同键同指纹重放首次成功结果，失败不占键；可空
 */
public record RejectRequest(List<String> keys, String reason, String calcKey) {
}
