package com.example.starter.web;

import com.example.starter.service.TimelineService;
import com.example.starter.service.TimelineService.PilotTimelineEntry;
import com.example.starter.service.TimelineService.VesselTimelineEntry;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 时间轴查询接口：按引航员或按船舶。
 */
@RestController
@RequestMapping("/api")
public class TimelineController {

    private final TimelineService timelineService;

    public TimelineController(TimelineService timelineService) {
        this.timelineService = timelineService;
    }

    @GetMapping("/pilots/{pilotCode}/timeline")
    public List<PilotTimelineEntry> pilotTimeline(@PathVariable String pilotCode) {
        return timelineService.pilotTimeline(pilotCode);
    }

    @GetMapping("/vessels/{vesselCode}/timeline")
    public List<VesselTimelineEntry> vesselTimeline(@PathVariable String vesselCode) {
        return timelineService.vesselTimeline(vesselCode);
    }
}