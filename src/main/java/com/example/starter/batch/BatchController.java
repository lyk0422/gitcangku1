package com.example.starter.batch;

import com.example.starter.batch.dto.AdjudicateExcursionRequest;
import com.example.starter.batch.dto.AdjudicationResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.ConfirmMinorExcursionRequest;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.DispositionRiskResponse;
import com.example.starter.batch.dto.ExcursionResponse;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RegisterExcursionsRequest;
import com.example.starter.batch.dto.ReleaseBlockResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SubmitTestRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 生产批次隔离与放行接口。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService service;

    public BatchController(BatchService service) {
        this.service = service;
    }

    /**
     * 创建批次，初始状态 QUARANTINED。
     */
    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody CreateBatchRequest request) {
        return stored(service.createBatch(request));
    }

    /**
     * 提交检验结果。
     */
    @PostMapping("/{batchKey}/tests")
    public ResponseEntity<String> submitTest(@PathVariable String batchKey,
                                             @Valid @RequestBody SubmitTestRequest request) {
        return stored(service.submitTest(batchKey, request));
    }

    /**
     * 提交放行批准；X-Actor-Id 为批准人，X-Approval-Role 为 QUALITY 或 OPERATIONS。
     */
    @PostMapping("/{batchKey}/approvals")
    public ResponseEntity<String> approve(@PathVariable String batchKey,
                                          @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                          @RequestHeader(name = "X-Approval-Role", required = false) String role,
                                          @Valid @RequestBody ApproveRequest request) {
        return stored(service.approve(batchKey, actorId, role, request));
    }

    /**
     * 召回已放行批次。
     */
    @PostMapping("/{batchKey}/recall")
    public ResponseEntity<String> recall(@PathVariable String batchKey,
                                         @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                         @Valid @RequestBody RecallRequest request) {
        return stored(service.recall(batchKey, actorId, request));
    }

    /**
     * 当前可用批次：不含已召回批次。
     */
    @GetMapping("/available")
    public List<BatchResponse> available() {
        return service.listAvailable();
    }

    /**
     * 批次完整历史明细：批次概要、全部检验、全部批准与召回记录。
     */
    @GetMapping("/{batchKey}/history")
    public BatchHistoryResponse history(@PathVariable String batchKey) {
        return service.history(batchKey);
    }

    /**
     * 拆分批次：仅当前可用的 RELEASED 父批可拆成 2～5 个全新子批，父批置为 SPLIT。
     */
    @PostMapping("/{batchKey}/split")
    public ResponseEntity<String> split(@PathVariable String batchKey,
                                        @Valid @RequestBody SplitRequest request) {
        return stored(service.split(batchKey, request));
    }

    /**
     * 祖先查询：从直接父批到根，含各批自身状态及导致不可用的召回祖先。
     */
    @GetMapping("/{batchKey}/ancestors")
    public List<LineageEntryResponse> ancestors(@PathVariable String batchKey) {
        return service.listAncestors(batchKey);
    }

    /**
     * 后代查询：按拆分创建顺序展开，含各批自身状态及导致不可用的召回祖先。
     */
    @GetMapping("/{batchKey}/descendants")
    public List<LineageEntryResponse> descendants(@PathVariable String batchKey) {
        return service.listDescendants(batchKey);
    }

    /**
     * 登记储运偏差（一次一条或多条，任一非法整批回滚）。
     */
    @PostMapping("/{batchKey}/excursions")
    public ResponseEntity<String> registerExcursions(@PathVariable String batchKey,
                                                     @Valid @RequestBody RegisterExcursionsRequest request) {
        return stored(service.registerExcursions(batchKey, request));
    }

    /**
     * 查询批次全部储运偏差区间。
     */
    @GetMapping("/{batchKey}/excursions")
    public List<ExcursionResponse> excursions(@PathVariable String batchKey) {
        return service.listExcursions(batchKey);
    }

    /**
     * 查询放行持续门禁：未裁决 MAJOR 与未确认 MINOR 偏差标识。
     */
    @GetMapping("/{batchKey}/release-block")
    public ReleaseBlockResponse releaseBlock(@PathVariable String batchKey) {
        return service.getReleaseBlock(batchKey);
    }

    /**
     * 裁决 MAJOR 偏差（REWORK/REJECT），写入不可变快照。
     */
    @PostMapping("/{batchKey}/excursions/{excursionKey}/adjudicate")
    public ResponseEntity<String> adjudicate(@PathVariable String batchKey,
                                             @PathVariable String excursionKey,
                                             @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                             @RequestHeader(name = "X-Approval-Role", required = false) String role,
                                             @Valid @RequestBody AdjudicateExcursionRequest request) {
        return stored(service.adjudicateExcursion(batchKey, excursionKey, actorId, role, request));
    }

    /**
     * MINOR 偏差质控确认。
     */
    @PostMapping("/{batchKey}/excursions/{excursionKey}/confirm")
    public ResponseEntity<String> confirmMinor(@PathVariable String batchKey,
                                               @PathVariable String excursionKey,
                                               @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                               @RequestHeader(name = "X-Approval-Role", required = false) String role,
                                               @Valid @RequestBody ConfirmMinorExcursionRequest request) {
        return stored(service.confirmMinorExcursion(batchKey, excursionKey, actorId, role, request));
    }

    /**
     * 查询批次 MAJOR 偏差裁决快照。
     */
    @GetMapping("/{batchKey}/adjudications")
    public List<AdjudicationResponse> adjudications(@PathVariable String batchKey) {
        return service.listAdjudications(batchKey);
    }

    /**
     * 查询批次处置风险记录。
     */
    @GetMapping("/{batchKey}/disposition-risks")
    public List<DispositionRiskResponse> dispositionRisks(@PathVariable String batchKey) {
        return service.listDispositionRisks(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
