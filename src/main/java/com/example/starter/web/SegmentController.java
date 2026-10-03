package com.example.starter.web;

import com.example.starter.model.AssignmentRow;
import com.example.starter.model.SegmentRow;
import com.example.starter.service.AssignmentService;
import com.example.starter.service.SegmentService;
import com.example.starter.web.dto.AssignRequest;
import com.example.starter.web.dto.AssignmentResponse;
import com.example.starter.web.dto.BoardRequest;
import com.example.starter.web.dto.BoardingPointChangeRequest;
import com.example.starter.web.dto.CreateSegmentRequest;
import com.example.starter.web.dto.DisembarkRequest;
import com.example.starter.web.dto.SegmentResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 航段申请、指派、登轮、离轮、撤销与登轮点变更接口。
 */
@RestController
@RequestMapping("/api/segments")
public class SegmentController {

    private final SegmentService segmentService;
    private final AssignmentService assignmentService;

    public SegmentController(SegmentService segmentService, AssignmentService assignmentService) {
        this.segmentService = segmentService;
        this.assignmentService = assignmentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SegmentResponse create(@Valid @RequestBody CreateSegmentRequest request) {
        SegmentRow row = segmentService.create(request.vesselCode(), request.requiredLevel(),
                request.estStartUtc(), request.estEndUtc(), request.boardingPoint());
        return SegmentResponse.from(row);
    }

    @PostMapping("/{id}/assign")
    public AssignmentResponse assign(@PathVariable long id,
                                     @Valid @RequestBody AssignRequest request) {
        AssignmentRow row = assignmentService.assign(id, request.pilotCode(), request.requestId());
        return AssignmentResponse.from(row);
    }

    @PostMapping("/{id}/board")
    public AssignmentResponse board(@PathVariable long id,
                                    @Valid @RequestBody BoardRequest request) {
        return AssignmentResponse.from(assignmentService.board(id, request.requestId()));
    }

    @PostMapping("/{id}/disembark")
    public AssignmentResponse disembark(@PathVariable long id,
                                        @Valid @RequestBody DisembarkRequest request) {
        return AssignmentResponse.from(assignmentService.disembark(id, request.disembarkedAtUtc()));
    }

    @PostMapping("/{id}/cancel-assignment")
    public AssignmentResponse cancelAssignment(@PathVariable long id) {
        return AssignmentResponse.from(assignmentService.cancelAssignment(id));
    }

    @PostMapping("/{id}/cancel")
    public SegmentResponse cancelByVessel(@PathVariable long id) {
        return SegmentResponse.from(segmentService.cancelByVessel(id));
    }

    @PutMapping("/{id}/boarding-point")
    public SegmentResponse changeBoardingPoint(@PathVariable long id,
                                               @Valid @RequestBody BoardingPointChangeRequest request) {
        return SegmentResponse.from(segmentService.changeBoardingPoint(id, request.boardingPoint()));
    }
}