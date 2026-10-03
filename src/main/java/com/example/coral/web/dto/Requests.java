package com.example.coral.web.dto;

import com.example.coral.domain.PlantStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 请求体DTO：校验失败返回422。
 */
public final class Requests {

    private Requests() {
    }

    /** 创建样方请求。 */
    public record CreateQuadratRequest(@NotBlank String quadratCode, String name) {
    }

    /** 移植登记请求：requestId幂等，固化来源苗圃、样方与UTC时刻。 */
    public record TransplantRequest(@NotBlank String requestId, @NotBlank String plantCode,
            @NotBlank String nursery, @NotBlank String quadratCode) {
    }

    /** 复核请求：result取ALIVE/DEAD/PENDING。 */
    public record ReviewRequest(@NotBlank String requestId, @NotBlank String plantCode,
            @NotNull PlantStatus result, String note) {
    }
}