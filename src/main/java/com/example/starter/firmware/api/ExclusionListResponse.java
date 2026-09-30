package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布单硬件不兼容排除清单响应（只读，发布人视角）。
 */
public record ExclusionListResponse(List<ExclusionView> exclusions) {
}
