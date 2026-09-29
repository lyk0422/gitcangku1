package com.example.starter.consent.ledger.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 受限批量查询请求：对指定主体、用途与授权代次下的一组对象执行受限查询。
 *
 * <p>服务端必须先校验全部对象（存在性、所属代次一致）与剩余额度，任一失败整体拒绝，
 * 不返回部分数据、不写账本、不扣减余额。
 *
 * @param requestId  幂等请求标识，同参重放首次完整成功响应快照，异参 409，失败不占键
 * @param subjectKey 主体标识
 * @param purpose    用途标识
 * @param epoch      期望查询的授权代次；对象跨代次时返回 422
 * @param objects    规范化对象规范集合，服务端去重后按字典序处理；不允许为空
 */
public record RestrictedQueryRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotBlank @Size(max = 32) String purpose,
        @NotNull @jakarta.validation.constraints.Min(1) Integer epoch,
        @NotEmpty @Valid List<@NotNull ObjectSpec> objects) {

    /**
     * 单个对象规范。
     *
     * @param recordKey 记录键（同一代内唯一）
     * @param count     调用方声明的对象数量（单位：个，正整数）；与记录实际数量不一致返回 422
     */
    public record ObjectSpec(
            @NotBlank @Size(max = 128) String recordKey,
            @NotNull @jakarta.validation.constraints.Min(1) Long count) {
    }
}
