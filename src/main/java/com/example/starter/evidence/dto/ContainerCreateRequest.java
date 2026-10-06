package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建封存箱请求。保管人取自 X-Actor-Id，初始 SEALED、封条 INTACT、版本 1。
 *
 * @param requestId    全局幂等请求键
 * @param containerKey 容器业务键，全局唯一
 * @param sealNo       箱独立封条编号
 */
public record ContainerCreateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String containerKey,
        @NotBlank @Size(max = 64) String sealNo) {
}
