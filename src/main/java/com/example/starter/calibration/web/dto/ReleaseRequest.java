package com.example.starter.calibration.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量放行请求，每批 1～50 条。
 */
public record ReleaseRequest(
        @NotNull(message = "ids 不能为空")
        @Size(min = 1, max = 50, message = "每批放行条数必须在 1～50 之间")
        List<Long> ids) {
}
