package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 异常证物重新封存申请请求。仅当前保管人可申请，指定见证人必须与自己不同；
 * resealKey 全局唯一；newSealNo 不得与本证物任一历史封条相同；reason 非空。
 *
 * @param commandKey 幂等命令键
 * @param resealKey  重新封存业务键，全局唯一
 * @param newSealNo  新封条号
 * @param witnessId  指定见证人
 * @param reason     重新封存原因，非空
 */
public record ResealRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String resealKey,
        @NotBlank @Size(max = 64) String newSealNo,
        @NotBlank @Size(max = 64) String witnessId,
        @NotBlank @Size(max = 512) String reason) {
}
