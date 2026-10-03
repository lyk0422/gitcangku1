package com.example.starter.web;

import com.example.starter.service.PilotageService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 引航航段 REST 入口：申请、指派、登轮、离轮、撤销、取消与时间轴查询。
 */
@RestController
@RequestMapping("/api")
public class PilotageController {

    private final PilotageService service;

    public PilotageController(PilotageService service) {
        this.service = service;
    }

    @PostMapping("/segments")
    public ResponseEntity<ApiDtos.SegmentResponse> createSegment(
            @Valid @RequestBody ApiDtos.CreateSegmentRequest request) {
        var segment = service.createSegment(request.vesselCode(), request.requiredGrade(), request.estStartUtc(),
                request.estEndUtc(), request.boardingPoint());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiDtos.SegmentResponse.of(segment));
    }

    @GetMapping("/segments/{segmentId}")
    public ApiDtos.SegmentResponse getSegment(@PathVariable long segmentId) {
        return ApiDtos.SegmentResponse.of(service.getSegment(segmentId));
    }

    @PutMapping("/segments/{segmentId}/boarding-point")
    public ApiDtos.SegmentResponse changeBoardingPoint(@PathVariable long segmentId,
                                                       @Valid @RequestBody ApiDtos.BoardingPointRequest request) {
        return ApiDtos.SegmentResponse.of(service.changeBoardingPoint(segmentId, request.boardingPoint()));
    }

    @PostMapping("/segments/{segmentId}/cancel")
    public ApiDtos.SegmentResponse cancelSegment(@PathVariable long segmentId) {
        return ApiDtos.SegmentResponse.of(service.cancelSegment(segmentId));
    }

    @PostMapping("/assignments")
    public ResponseEntity<ApiDtos.AssignmentResponse> assign(@Valid @RequestBody ApiDtos.AssignRequest request) {
        var outcome = service.assign(request.requestId(), request.segmentId(), request.pilotId());
        HttpStatus status = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiDtos.AssignmentResponse.of(outcome.assignment(),
                outcome.replayed()));
    }

    @PostMapping("/assignments/{assignmentId}/boarding")
    public ResponseEntity<ApiDtos.AssignmentResponse> board(@PathVariable long assignmentId,
                                                            @Valid @RequestBody ApiDtos.BoardRequest request) {
        var outcome = service.board(assignmentId, request.requestId());
        HttpStatus status = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiDtos.AssignmentResponse.of(outcome.assignment(),
                outcome.replayed()));
    }

    @PostMapping("/assignments/{assignmentId}/complete")
    public ApiDtos.AssignmentResponse complete(@PathVariable long assignmentId) {
        return ApiDtos.AssignmentResponse.of(service.complete(assignmentId), false);
    }

    @PostMapping("/assignments/{assignmentId}/revoke")
    public ApiDtos.AssignmentResponse revoke(@PathVariable long assignmentId) {
        return ApiDtos.AssignmentResponse.of(service.revoke(assignmentId), false);
    }

    @GetMapping("/pilots/{pilotId}/timeline")
    public ApiDtos.TimelineResponse<ApiDtos.PilotTimelineEntry> pilotTimeline(@PathVariable long pilotId) {
        var entries = service.pilotTimeline(pilotId).stream().map(ApiDtos.PilotTimelineEntry::of).toList();
        return new ApiDtos.TimelineResponse<>(entries);
    }

    @GetMapping("/vessels/{vesselCode}/timeline")
    public ApiDtos.TimelineResponse<ApiDtos.VesselTimelineEntry> vesselTimeline(@PathVariable String vesselCode) {
        var entries = service.vesselTimeline(vesselCode).stream().map(ApiDtos.VesselTimelineEntry::of).toList();
        return new ApiDtos.TimelineResponse<>(entries);
    }
}