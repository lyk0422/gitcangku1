package com.example.starter.evidence.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 装箱/拆箱请求。成员变更携带根箱 expectedVersion，成功后根箱版本加一。
 *
 * @param requestId       全局幂等请求键
 * @param memberType      成员类型：EVIDENCE 证物 / BOX 子箱
 * @param memberKey       成员业务键
 * @param expectedVersion 根箱当前成员版本（乐观并发控制，不匹配返回 409）
 */
public record ContainerMemberRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 10) String memberType,
        @NotBlank @Size(max = 64) String memberKey,
        @NotNull @Min(1) Integer expectedVersion) {
}
