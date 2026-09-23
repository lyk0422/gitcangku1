package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 双人重新封存申请请求。仅 SEAL_BROKEN 证物的当前保管人可提交；
 * resealKey 全局唯一，见证人必须与申请人不同，新封条号不得与本证物任一历史封条相同。
 *
 * @param commandKey 幂等命令键
 * @param resealKey  重新封存业务键，全局唯一
 * @param witnessId  指定见证人，必须与申请人不同
 * @param reason     重新封存原因，非空
 * @param newSealNo  拟换用的新封条号
 */
public record ResealApplyRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String resealKey,
        @NotBlank @Size(max = 64) String witnessId,
        @NotBlank @Size(max = 512) String reason,
        @NotBlank @Size(max = 64) String newSealNo) {
}
