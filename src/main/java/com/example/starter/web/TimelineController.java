package com.example.starter.web;

import com.example.starter.domain.TimelineEvent;
import com.example.starter.service.TimelineService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 历史时间线查询：按标签或动物展示站点、交接确认时刻及拒绝原因。
 */
@RestController
@RequestMapping("/api")
public class TimelineController {

    private final TimelineService timelineService;

    public TimelineController(TimelineService timelineService) {
        this.timelineService = timelineService;
    }

    /**
     * 按标签查询完整时间线。
     */
    @GetMapping("/tags/{tagCode}/timeline")
    public List<TimelineEvent> tagTimeline(@PathVariable String tagCode) {
        return timelineService.tagTimeline(tagCode);
    }

    /**
     * 按动物查询完整时间线。
     */
    @GetMapping("/animals/{animalCode}/timeline")
    public List<TimelineEvent> animalTimeline(@PathVariable String animalCode) {
        return timelineService.animalTimeline(animalCode);
    }
}