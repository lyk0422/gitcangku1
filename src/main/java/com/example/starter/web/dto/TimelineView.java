package com.example.starter.web.dto;

import java.util.List;

/**
 * 仪器时间轴视图：该仪器已分配窗口、全部申请状态、候补顺序与接替流水。
 */
public record TimelineView(
        String instrumentId,
        List<RequestView> allocated,
        List<RequestView> requests,
        List<RequestView> waitingQueue,
        List<SuccessionView> successions
) {
}
