package com.example.starter.batch;

import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.ContributionResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.MergeRequest;
import com.example.starter.batch.dto.MergeResponse;
import com.example.starter.batch.dto.PotencyModelResponse;
import com.example.starter.batch.dto.PotencyViewResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.SetPotencyModelRequest;
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
 * 生产批次隔离、效价衰减与放行接口。
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
     * 第二笔批准按当前效价核验，低于最低放行效价返回 422。
     */
    @PostMapping("/{batchKey}/approvals")
    public ResponseEntity<String> approve(@PathVariable String batchKey,
                                          @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                          @RequestHeader(name = "X-Approval-Role", required = false) String role,
                                          @Valid @RequestBody ApproveRequest request) {
        return stored(service.approve(batchKey, actorId, role, request));
    }

    /**
     * 召回已放行（含已拆分/已合批）批次。
     */
    @PostMapping("/{batchKey}/recall")
    public ResponseEntity<String> recall(@PathVariable String batchKey,
                                         @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                         @Valid @RequestBody RecallRequest request) {
        return stored(service.recall(batchKey, actorId, request));
    }

    /**
     * 当前可用批次：不含已召回、已拆分、已合批批次。
     */
    @GetMapping("/available")
    public List<BatchResponse> available() {
        return service.listAvailable();
    }

    /**
     * 批次完整历史明细：批次概要、全部检验、全部批准、召回记录与固化效价证据。
     */
    @GetMapping("/{batchKey}/history")
    public BatchHistoryResponse history(@PathVariable String batchKey) {
        return service.history(batchKey);
    }

    /**
     * 登记/修订批次效价模型（版本只增不改）；X-Actor-Id 为操作者。
     */
    @PostMapping("/{batchKey}/potency-model")
    public ResponseEntity<String> setPotencyModel(@PathVariable String batchKey,
                                                  @RequestHeader(name = "X-Actor-Id", required = false)
                                                  String actorId,
                                                  @Valid @RequestBody SetPotencyModelRequest request) {
        return stored(service.setPotencyModel(batchKey, actorId, request));
    }

    /**
     * 效价诊断：当前模型版本、此刻效价/要求值/差额与全部固化历史证据。
     */
    @GetMapping("/{batchKey}/potency")
    public PotencyViewResponse potency(@PathVariable String batchKey) {
        return service.potencyView(batchKey);
    }

    /**
     * 拆分批次：仅当前可用的 RELEASED 父批可拆成 2～5 个全新子批，父批置为 SPLIT；
     * 子批初始效价取父批拆分时刻当前效价。
     */
    @PostMapping("/{batchKey}/split")
    public ResponseEntity<String> split(@PathVariable String batchKey,
                                        @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                        @Valid @RequestBody SplitRequest request) {
        return stored(service.split(batchKey, actorId, request));
    }

    /**
     * 多父合批：2～5 个 RELEASED 父批按投入数量加权合成新子批；X-Actor-Id 为操作者。
     */
    @PostMapping("/merge")
    public ResponseEntity<String> merge(@RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                        @Valid @RequestBody MergeRequest request) {
        return stored(service.merge(actorId, request));
    }

    /**
     * 合批父贡献明细：父批键、实际数量、实际权重与操作时刻输入效价，按 seq 稳定排序。
     */
    @GetMapping("/{batchKey}/contributions")
    public List<ContributionResponse> contributions(@PathVariable String batchKey) {
        return service.listContributions(batchKey);
    }

    /**
     * 祖先查询：从直接父批到根（合批子批可有多个父批），含各批状态及导致不可用的召回祖先。
     */
    @GetMapping("/{batchKey}/ancestors")
    public List<LineageEntryResponse> ancestors(@PathVariable String batchKey) {
        return service.listAncestors(batchKey);
    }

    /**
     * 后代查询：按拆分/合批创建顺序展开，含各批状态及导致不可用的召回祖先。
     */
    @GetMapping("/{batchKey}/descendants")
    public List<LineageEntryResponse> descendants(@PathVariable String batchKey) {
        return service.listDescendants(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
