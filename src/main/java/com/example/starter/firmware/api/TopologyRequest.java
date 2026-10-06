package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 拓扑波次发布绑定的设备无向图：最多10台设备，边表示相邻（不能同时停机升级）。
 * 图在发布单创建成功时冻结，发布后不可改。详细校验（自环、重复边、端点归属、设备存在与型号匹配）
 * 在服务端事务内完成。
 */
public record TopologyRequest(
        @NotNull @Positive Integer topologyVersion,
        @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 64) String> deviceIds,
        @NotNull @Size(max = 45) List<@Valid TopologyEdgeRequest> edges) {
}
