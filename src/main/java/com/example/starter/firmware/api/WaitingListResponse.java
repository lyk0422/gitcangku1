package com.example.starter.firmware.api;

import java.util.List;

/**
 * 区域等待清单响应：按等待时刻升序、同刻按设备ID字典序。
 */
public record WaitingListResponse(List<WaitingDeviceView> devices) {
}
