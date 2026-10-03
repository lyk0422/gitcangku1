package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 入参 DTO 集合。
 */
public final class Requests {

    private Requests() {
    }

    /**
     * 层位登记请求。
     *
     * @param siteCode 遗址编号（合成数据）
     * @param stratumNo 层位号
     * @param description 层位描述，可为空
     */
    public record RegisterStratumRequest(@NotBlank String siteCode, @NotBlank String stratumNo,
            @Size(max = 512) String description) {
    }

    /**
     * 样本采集请求。
     *
     * @param requestId 幂等请求标识
     * @param sampleNo 样本编号，全项目唯一
     * @param siteCode 遗址编号
     * @param stratumNo 层位号
     * @param gridRef 采集坐标格
     */
    public record CollectSampleRequest(@NotBlank String requestId, @NotBlank String sampleNo,
            @NotBlank String siteCode, @NotBlank String stratumNo, @NotBlank String gridRef) {
    }

    /**
     * 建包请求。
     *
     * @param siteCode 遗址编号
     * @param stratumNo 层位号
     */
    public record CreatePackageRequest(@NotBlank String siteCode, @NotBlank String stratumNo) {
    }

    /**
     * 入包请求。
     *
     * @param sampleNo 样本编号
     */
    public record AddItemRequest(@NotBlank String sampleNo) {
    }

    /**
     * 交接请求。
     *
     * @param requestId 幂等请求标识
     * @param sealNo 封签编号，交接时锁定
     * @param receiver 接收方（合成数据），可为空
     */
    public record HandoverRequest(@NotBlank String requestId, @NotBlank String sealNo,
            @Size(max = 128) String receiver) {
    }

    /**
     * 封签破损上报请求。
     *
     * @param note 异常说明
     */
    public record DamageRequest(@NotBlank @Size(max = 512) String note) {
    }
}