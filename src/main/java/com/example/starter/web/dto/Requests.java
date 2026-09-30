package com.example.starter.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 接口请求体集合。
 */
public final class Requests {

    private Requests() {
    }

    /**
     * 建立合成种子批次。
     *
     * @param batchNo  批次编号，业务唯一
     * @param cropName 作物名称
     */
    public record CreateBatchRequest(
            @NotBlank(message = "批次编号不能为空")
            String batchNo,
            @NotBlank(message = "作物名称不能为空")
            String cropName
    ) {
    }

    /**
     * 登记一次发芽率检测（复检同样调用本接口追加，不覆盖历史记录）。
     *
     * @param testNo           检测编号，全库唯一，同一编号只能归属一个批次
     * @param sampleSize       样本数，单位粒，必须在 100-400 之间
     * @param germinatedCount  发芽粒数，单位粒，不得大于样本数
     * @param testedAt         检测时刻（Asia/Shanghai）；为空时取服务当前时刻，决定“最近两次”次序
     */
    public record RegisterTestRequest(
            @NotBlank(message = "检测编号不能为空")
            String testNo,
            @NotNull(message = "样本数不能为空")
            @Min(value = 100, message = "样本数不得少于100粒")
            @Max(value = 400, message = "样本数不得多于400粒")
            Integer sampleSize,
            @NotNull(message = "发芽粒数不能为空")
            @Min(value = 0, message = "发芽粒数不能为负")
            Integer germinatedCount,
            LocalDateTime testedAt
    ) {
        /**
         * 跨字段校验：发芽粒数不得大于样本数。
         */
        public boolean germinatedNotExceedSample() {
            return sampleSize == null || germinatedCount == null || germinatedCount <= sampleSize;
        }
    }

    /**
     * 签发证书请求。
     *
     * @param requestId 签发幂等键；同参重放返回首次响应，异参返回 409
     */
    public record IssueCertificateRequest(
            @NotBlank(message = "requestId不能为空")
            String requestId
    ) {
    }

    /**
     * 批次放行请求。
     *
     * @param requestId 放行幂等键；同参重放返回首次响应，异参返回 409
     */
    public record ReleaseRequest(
            @NotBlank(message = "requestId不能为空")
            String requestId
    ) {
    }
}
