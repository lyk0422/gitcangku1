package com.example.starter.exposure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 预算转移激活请求。requestId 幂等、transferKey 全局唯一；
 * 激活在一个事务内整体生效或整体回滚，不逐条转移。
 *
 * @param requestId   写操作全局唯一幂等键；同参重放首次快照，明细换序等价，异参 409
 * @param transferKey 转移单业务编号，全局唯一
 * @param lines       转移明细，2～50 条，按源目标规范化求和后生效
 */
public record ActivateTransferRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String transferKey,
        @NotNull @Size(min = 2, max = 50) List<@Valid TransferLineRequest> lines
) {
}
