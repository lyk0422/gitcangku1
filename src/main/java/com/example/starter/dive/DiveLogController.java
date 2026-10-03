package com.example.starter.dive;

import com.example.starter.dive.DiveDtos.AppendPointsRequest;
import com.example.starter.dive.DiveDtos.AppendResult;
import com.example.starter.dive.DiveDtos.CreateLogRequest;
import com.example.starter.dive.DiveDtos.LogDetailDto;
import com.example.starter.dive.DiveDtos.LogDto;
import com.example.starter.dive.DiveDtos.ReviewDto;
import com.example.starter.dive.DiveDtos.ReviewRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 潜水训练作业记录接口：点位上传、上浮审核、勘误复核与查询。
 */
@RestController
@RequestMapping("/api/dive-logs")
public class DiveLogController {

    private final DiveLogService service;

    public DiveLogController(DiveLogService service) {
        this.service = service;
    }

    /** 创建作业记录。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LogDto create(@RequestBody CreateLogRequest request) {
        return service.createLog(request);
    }

    /** 追加点位（原始点或勘误点）。 */
    @PostMapping("/{id}/points")
    public AppendResult appendPoints(@PathVariable long id, @RequestBody AppendPointsRequest request) {
        return service.appendPoints(id, request);
    }

    /** 发起上浮审核。 */
    @PostMapping("/{id}/reviews")
    public ReviewDto review(@PathVariable long id, @RequestBody ReviewRequest request) {
        return service.review(id, request);
    }

    /** 查询作业详情：原始点、异常区间、审核版本与复核链。 */
    @GetMapping("/{id}")
    public LogDetailDto detail(@PathVariable long id) {
        return service.detail(id);
    }
}