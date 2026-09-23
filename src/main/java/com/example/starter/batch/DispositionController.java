package com.example.starter.batch;

import com.example.starter.batch.dto.ClosureEntryResponse;
import com.example.starter.batch.dto.DispositionCancelRequest;
import com.example.starter.batch.dto.DispositionConfirmRequest;
import com.example.starter.batch.dto.DispositionOrderResponse;
import com.example.starter.batch.dto.DispositionRejectRequest;
import com.example.starter.batch.dto.DispositionSnapshotResponse;
import com.example.starter.batch.dto.DispositionSubmitRequest;
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
 * 召回处置单接口：质量负责人提交、生产负责人确认/拒绝、提交人取消，以及只读查询。
 */
@RestController
@RequestMapping("/api")
public class DispositionController {

    private final DispositionService service;

    public DispositionController(DispositionService service) {
        this.service = service;
    }

    /**
     * 对 RECALLED 祖先提交召回处置单（X-Approval-Role=QUALITY）。
     */
    @PostMapping("/batches/{ancestorKey}/dispositions")
    public ResponseEntity<String> submit(@PathVariable String ancestorKey,
                                         @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                         @RequestHeader(name = "X-Approval-Role", required = false) String role,
                                         @Valid @RequestBody DispositionSubmitRequest request) {
        return stored(service.submit(ancestorKey, actorId, role, request));
    }

    /**
     * 生产负责人二审确认：重校闭包与版本后在一个事务内按分类落账全部批次。
     */
    @PostMapping("/dispositions/{dispositionKey}/confirm")
    public ResponseEntity<String> confirm(@PathVariable String dispositionKey,
                                          @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                          @RequestHeader(name = "X-Approval-Role", required = false) String role,
                                          @Valid @RequestBody DispositionConfirmRequest request) {
        return stored(service.confirm(dispositionKey, actorId, role, request));
    }

    /**
     * 生产负责人拒绝处置单；不改批次。
     */
    @PostMapping("/dispositions/{dispositionKey}/reject")
    public ResponseEntity<String> reject(@PathVariable String dispositionKey,
                                         @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                         @RequestHeader(name = "X-Approval-Role", required = false) String role,
                                         @Valid @RequestBody DispositionRejectRequest request) {
        return stored(service.reject(dispositionKey, actorId, role, request));
    }

    /**
     * 确认/拒绝前由提交人本人取消处置单；不改批次。
     */
    @PostMapping("/dispositions/{dispositionKey}/cancel")
    public ResponseEntity<String> cancel(@PathVariable String dispositionKey,
                                         @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                         @RequestHeader(name = "X-Approval-Role", required = false) String role,
                                         @Valid @RequestBody DispositionCancelRequest request) {
        return stored(service.cancel(dispositionKey, actorId, role, request));
    }

    /**
     * 处置单只读查询：元数据与提交时冻结的闭包批次（版本/状态/路径/分类）。
     */
    @GetMapping("/dispositions/{dispositionKey}")
    public DispositionOrderResponse getOrder(@PathVariable String dispositionKey) {
        return service.getOrder(dispositionKey);
    }

    /**
     * 不可变路径与分类快照查询：仅 CONFIRMED 处置单存在。
     */
    @GetMapping("/dispositions/{dispositionKey}/paths")
    public List<DispositionSnapshotResponse> paths(@PathVariable String dispositionKey) {
        return service.paths(dispositionKey);
    }

    /**
     * 召回闭包只读查询：对 RECALLED 祖先返回含自身的当前后代闭包及完整路径。
     */
    @GetMapping("/batches/{ancestorKey}/closure")
    public List<ClosureEntryResponse> closure(@PathVariable String ancestorKey) {
        return service.closure(ancestorKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
