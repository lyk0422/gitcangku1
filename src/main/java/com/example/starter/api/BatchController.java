package com.example.starter.api;

import com.example.starter.api.dto.AddRollRequest;
import com.example.starter.api.dto.BatchView;
import com.example.starter.api.dto.CreateBatchRequest;
import com.example.starter.api.dto.ErrataRequest;
import com.example.starter.api.dto.MeasurementRequest;
import com.example.starter.api.dto.MeasurementResponse;
import com.example.starter.api.dto.ReleaseRequest;
import com.example.starter.api.dto.ReleaseResponse;
import com.example.starter.api.dto.RulingRequest;
import com.example.starter.api.dto.UpdateTargetRequest;
import com.example.starter.repo.DyeBatchRepository.BatchRow;
import com.example.starter.repo.DyeBatchRepository.ErrataRow;
import com.example.starter.repo.DyeBatchRepository.RollRow;
import com.example.starter.repo.DyeBatchRepository.RulingRow;
import com.example.starter.service.DyeBatchService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 染色批次放行链路 REST 入口。
 */
@RestController
@RequestMapping("/api")
public class BatchController {

    private final DyeBatchService service;

    public BatchController(DyeBatchService service) {
        this.service = service;
    }

    /** 创建染色批次。 */
    @PostMapping("/batches")
    public Map<String, Object> createBatch(@Valid @RequestBody CreateBatchRequest req) {
        BatchRow batch = service.createBatch(req.batchNo(), req.targetL(), req.targetA(), req.targetB());
        return Map.of("batchId", batch.id(), "batchNo", batch.batchNo(),
                "targetVersion", batch.targetVersion());
    }

    /** 修改目标值（版本递增）。 */
    @PutMapping("/batches/{id}/target")
    public Map<String, Object> updateTarget(@PathVariable long id,
            @Valid @RequestBody UpdateTargetRequest req) {
        BatchRow batch = service.updateTarget(id, req.targetL(), req.targetA(), req.targetB());
        return Map.of("batchId", batch.id(), "targetVersion", batch.targetVersion());
    }

    /** 添加布卷。 */
    @PostMapping("/batches/{id}/rolls")
    public Map<String, Object> addRoll(@PathVariable long id, @Valid @RequestBody AddRollRequest req) {
        RollRow roll = service.addRoll(id, req.rollNo());
        return Map.of("rollId", roll.id(), "rollNo", roll.rollNo());
    }

    /** 上报测色（原测或复检），requestId 幂等。 */
    @PostMapping("/rolls/{id}/measurements")
    public MeasurementResponse measure(@PathVariable long id,
            @Valid @RequestBody MeasurementRequest req) {
        return service.recordMeasurement(id, req.kind(), req.requestId(),
                req.l(), req.a(), req.b(), req.measuredAt());
    }

    /** 复检裁决。 */
    @PostMapping("/rolls/{id}/ruling")
    public Map<String, Object> rule(@PathVariable long id, @Valid @RequestBody RulingRequest req) {
        RulingRow ruling = service.ruleRecheck(id, req.decision(), req.inspector());
        return Map.of("rollId", ruling.rollId(), "decision", ruling.decision(),
                "inspector", ruling.inspector());
    }

    /** 整批放行，requestId 幂等。 */
    @PostMapping("/batches/{id}/release")
    public ReleaseResponse release(@PathVariable long id, @Valid @RequestBody ReleaseRequest req) {
        return service.release(id, req.requestId());
    }

    /** 放行后追加勘误。 */
    @PostMapping("/releases/{id}/errata")
    public Map<String, Object> errata(@PathVariable long id, @Valid @RequestBody ErrataRequest req) {
        ErrataRow errata = service.appendErrata(id, req.note());
        return Map.of("errataId", errata.id(), "releaseId", errata.releaseId());
    }

    /** 查询批次视图。 */
    @GetMapping("/batches/{id}")
    public BatchView view(@PathVariable long id) {
        return service.getBatchView(id);
    }
}