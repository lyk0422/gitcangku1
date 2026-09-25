package com.example.starter.batch;

import com.example.starter.batch.dto.ApproveReleaseRequest;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.RecallImpactEntry;
import com.example.starter.batch.dto.RecallReleaseApplyRequest;
import com.example.starter.batch.dto.RecallReleaseDetailResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RetestGapResponse;
import com.example.starter.batch.dto.RetestRequest;
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
     * 提交召回复检：仅处于召回影响范围的批次可提交，复检不改变批次状态。
     */
    @PostMapping("/{batchKey}/retests")
    public ResponseEntity<String> submitRetest(@PathVariable String batchKey,
                                               @Valid @RequestBody RetestRequest request) {
        return stored(service.submitRetest(batchKey, request));
    }

    /**
     * 申请召回解除：仅根召回记录为 ACTIVE 的批次可申请；X-Actor-Id 为申请人。
     */
    @PostMapping("/{batchKey}/recall-releases")
    public ResponseEntity<String> applyRecallRelease(
            @PathVariable String batchKey,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody RecallReleaseApplyRequest request) {
        return stored(service.applyRecallRelease(batchKey, actorId, request));
    }

    /**
     * 批准召回解除：按最终血缘闭包预校验，任一失败 422 且状态不变；X-Actor-Id 为审批人。
     */
    @PostMapping("/{batchKey}/recall-releases/{releaseKey}/approve")
    public ResponseEntity<String> approveRecallRelease(
            @PathVariable String batchKey,
            @PathVariable String releaseKey,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody ApproveReleaseRequest request) {
        return stored(service.approveRecallRelease(batchKey, releaseKey, actorId, request));
    }

    /**
     * 解除申请详情：申请本体 + 批准后的不可变评审快照。
     */
    @GetMapping("/{batchKey}/recall-releases/{releaseKey}")
    public RecallReleaseDetailResponse recallReleaseDetail(@PathVariable String batchKey,
                                                           @PathVariable String releaseKey) {
        return service.recallReleaseDetail(batchKey, releaseKey);
    }

    /**
     * 召回血缘影响查询：闭包内各批自身状态、最新召回代次与复检合格性。
     */
    @GetMapping("/{batchKey}/recall-impact")
    public List<RecallImpactEntry> recallImpact(@PathVariable String batchKey) {
        return service.recallImpact(batchKey);
    }

    /**
     * 复检缺口查询：最终血缘闭包内未满足解除条件的批次及可区分原因。
     */
    @GetMapping("/{batchKey}/retest-gaps")
    public RetestGapResponse retestGaps(@PathVariable String batchKey) {
        return service.retestGaps(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
