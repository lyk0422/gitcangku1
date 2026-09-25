package com.example.starter.race.api;

import com.example.starter.race.service.CourseService;
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
 * 赛道登记、纪录认定与纪录查询 HTTP 接口。
 */
@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    /** 登记赛道。 */
    @PostMapping
    public ResponseEntity<Object> registerCourse(@Valid @RequestBody RegisterCourseRequest request) {
        return toResponse(courseService.registerCourse(request));
    }

    /** 提交纪录认定申请。 */
    @PostMapping("/{courseKey}/record-claims")
    public ResponseEntity<Object> claimRecord(
            @PathVariable String courseKey,
            @Valid @RequestBody ClaimRecordRequest request) {
        return toResponse(courseService.claimRecord(courseKey, request));
    }

    /** 查询赛道登记信息（含当前纪录ID）。 */
    @GetMapping("/{courseKey}")
    public CourseResponse getCourse(@PathVariable String courseKey) {
        return courseService.getCourse(courseKey);
    }

    /** 查询当前纪录与完整历史纪录链（只读）。 */
    @GetMapping("/{courseKey}/records")
    public CourseRecordHistoryResponse getRecordHistory(@PathVariable String courseKey) {
        return courseService.getRecordHistory(courseKey);
    }

    private ResponseEntity<Object> toResponse(ServiceResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
