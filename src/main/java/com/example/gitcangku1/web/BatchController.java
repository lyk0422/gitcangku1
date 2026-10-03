package com.example.gitcangku1.web;

import com.example.gitcangku1.domain.Adjudication;
import com.example.gitcangku1.domain.DyeBatch;
import com.example.gitcangku1.domain.Measurement;
import com.example.gitcangku1.service.BatchService;
import com.example.gitcangku1.service.ReleaseService;
import com.example.gitcangku1.service.Views.BatchView;
import com.example.gitcangku1.service.Views.ErrataView;
import com.example.gitcangku1.service.Views.ReleaseView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService batchService;
    private final ReleaseService releaseService;

    public BatchController(BatchService batchService, ReleaseService releaseService) {
        this.batchService = batchService;
        this.releaseService = releaseService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DyeBatch create(@Valid @RequestBody ApiDtos.CreateBatchRequest req) {
        return batchService.createBatch(req.batchNo(), req.targetL(), req.targetA(), req.targetB(),
                req.expectedRolls());
    }

    @PutMapping("/{batchId}/target")
    public DyeBatch updateTarget(@PathVariable long batchId,
            @Valid @RequestBody ApiDtos.UpdateTargetRequest req) {
        return batchService.updateTarget(batchId, req.targetL(), req.targetA(), req.targetB());
    }

    @PostMapping("/{batchId}/measurements")
    @ResponseStatus(HttpStatus.CREATED)
    public Measurement measure(@PathVariable long batchId,
            @Valid @RequestBody ApiDtos.MeasurementRequest req) {
        return batchService.recordMeasurement(batchId, req.requestId(), req.rollNo(), req.type(),
                req.l(), req.a(), req.b(), req.measuredAt());
    }

    @PostMapping("/{batchId}/rolls/{rollNo}/adjudication")
    @ResponseStatus(HttpStatus.CREATED)
    public Adjudication adjudicate(@PathVariable long batchId, @PathVariable int rollNo,
            @Valid @RequestBody ApiDtos.AdjudicationRequest req) {
        return batchService.adjudicate(batchId, rollNo, req.decision(), req.inspector());
    }

    @PostMapping("/{batchId}/release")
    public ReleaseView release(@PathVariable long batchId,
            @Valid @RequestBody ApiDtos.ReleaseRequest req) {
        return releaseService.release(batchId, req.requestId(), req.expectedTargetVersion());
    }

    @GetMapping("/{batchId}")
    public BatchView view(@PathVariable long batchId) {
        return releaseService.view(batchId);
    }

    @PostMapping("/{batchId}/errata")
    @ResponseStatus(HttpStatus.CREATED)
    public ErrataView errata(@PathVariable long batchId,
            @Valid @RequestBody ApiDtos.ErrataRequest req) {
        return releaseService.appendErrata(batchId, req.note());
    }
}