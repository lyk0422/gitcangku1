package com.example.starter.firmware.api;

import java.util.List;

/**
 * 区域限流历史响应：按发生顺序升序。
 */
public record ThrottleEventListResponse(List<ThrottleEventView> events) {
}
