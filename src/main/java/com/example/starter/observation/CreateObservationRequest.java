package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建观测记录请求。
 *
 * @param requestId     全局唯一请求标识（幂等去重键）
 * @param observationId 观测记录唯一标识
 * @param location      观测地点
 * @param reading       观测读数，十进制字符串，最多三位小数
 * @param note          观测备注
 * @param dx            可选位移 X 分量（米，十进制字符串，最多三位小数）；与 dy/w 必须同时提供或同时缺省
 * @param dy            可选位移 Y 分量（米，十进制字符串，最多三位小数）
 * @param w             可选边长权重（米，正数十进制字符串，最多三位小数）
 */
public record CreateObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note,
        @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "dx must be a decimal string with at most 3 fraction digits")
        String dx,
        @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "dy must be a decimal string with at most 3 fraction digits")
        String dy,
        @Pattern(regexp = "\\d+(\\.\\d{1,3})?", message = "w must be a non-negative decimal string with at most 3 fraction digits")
        String w) {

    /**
     * 无位移场景的便捷构造：dx/dy/w 均为 null。
     */
    public CreateObservationRequest(String requestId, String observationId, String location,
                                    String reading, String note) {
        this(requestId, observationId, location, reading, note, null, null, null);
    }
}
