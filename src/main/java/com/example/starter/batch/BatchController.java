package com.example.starter.batch;

import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.ConfirmExtensionRequest;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.ShelfLifeResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SubmitExtensionRequest;
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
     * 当前可用批次：不含已召回、已拆分与已到期批次。
     */
    @GetMapping("/available")
    public List<BatchResponse> available() {
        return service.listAvailable();
    }

    /**
     * 到期批次清单：服务端当前时刻已达到有效期的全部批次，只读稳定排序。
     */
    @GetMapping("/expired")
    public List<BatchResponse> expired() {
        return service.listExpired();
    }

    /**
     * 批次完整历史明细：批次概要、全部检验、全部批准、召回记录与复检延期历史。
     */
    @GetMapping("/{batchKey}/history")
    public BatchHistoryResponse history(@PathVariable String batchKey) {
        return service.history(batchKey);
    }

    /**
     * 有效期查询：当前有效期、剩余分钟、累计顺延、延期次数与延期历史。
     */
    @GetMapping("/{batchKey}/shelf-life")
    public ShelfLifeResponse shelfLife(@PathVariable String batchKey) {
        return service.shelfLife(batchKey);
    }

    /**
     * 提交复检延期：X-Actor-Id 为与原两名批准人都不同的复检人；结论须合格。
     */
    @PostMapping("/{batchKey}/extensions")
    public ResponseEntity<String> submitExtension(@PathVariable String batchKey,
                                                  @RequestHeader(name = "X-Actor-Id", required = false)
                                                  String actorId,
                                                  @Valid @RequestBody SubmitExtensionRequest request) {
        return stored(service.submitExtension(batchKey, actorId, request));
    }

    /**
     * 确认复检延期：X-Actor-Id 为不同于复检人的批准角色，确认后在同一事务内顺延有效期。
     */
    @PostMapping("/extensions/{extensionKey}/confirm")
    public ResponseEntity<String> confirmExtension(@PathVariable String extensionKey,
                                                   @RequestHeader(name = "X-Actor-Id", required = false)
                                                   String actorId,
                                                   @RequestHeader(name = "X-Approval-Role", required = false)
                                                   String role,
                                                   @Valid @RequestBody ConfirmExtensionRequest request) {
        return stored(service.confirmExtension(extensionKey, actorId, role, request));
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

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
