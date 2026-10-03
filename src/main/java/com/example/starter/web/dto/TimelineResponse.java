package com.example.starter.web.dto;

import com.example.starter.service.DeicingService.VehicleTimeline;
import java.util.List;

/** 车辆时间轴视图：车辆信息及按时隙开始升序的条目。 */
public record TimelineResponse(long vehicleId, String vehicleCode, String vehicleName,
        List<TimelineEntryResponse> entries) {

    public static TimelineResponse of(VehicleTimeline timeline) {
        List<TimelineEntryResponse> entries = timeline.entries().stream()
                .map(TimelineEntryResponse::of).toList();
        return new TimelineResponse(timeline.vehicle().id(), timeline.vehicle().code(),
                timeline.vehicle().name(), entries);
    }
}