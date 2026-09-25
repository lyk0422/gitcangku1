package com.example.starter.race.api;

import com.example.starter.race.service.RaceService;
import com.example.starter.race.service.ServiceResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 赛道登记与赛道纪录（认定、当前纪录、历史纪录链）HTTP 接口。
 */
@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final RaceService raceService;

    public CourseController(RaceService raceService) {
        this.raceService = raceService;
    }

    /** 登记赛道（初始无纪录）。 */
    @PostMapping
    public ResponseEntity<Object> registerCourse(@Valid @RequestBody RegisterCourseRequest request) {
        return toResponse(raceService.registerCourse(request));
    }

    /** 提交赛道纪录认定申请。 */
    @PostMapping("/{courseKey}/record-claims")
    public ResponseEntity<Object> claimRecord(
            @PathVariable String courseKey,
            @Valid @RequestBody ClaimRecordRequest request) {
        return toResponse(raceService.claimRecord(courseKey, request));
    }

    /** 查询赛道当前纪录（只读，不触发认定）。 */
    @GetMapping("/{courseKey}/record")
    public CourseRecordResponse getCurrentRecord(@PathVariable String courseKey) {
        return raceService.getCurrentRecord(courseKey);
    }

    /** 查询赛道完整历史纪录链（只读，不触发认定）。 */
    @GetMapping("/{courseKey}/record-history")
    public RecordHistoryResponse getRecordHistory(@PathVariable String courseKey) {
        return raceService.getRecordHistory(courseKey);
    }

    private ResponseEntity<Object> toResponse(ServiceResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
