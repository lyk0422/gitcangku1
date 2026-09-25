package com.example.starter.container;

import com.example.starter.container.dto.BlockReasonView;
import com.example.starter.container.dto.ContainerCreateRequest;
import com.example.starter.container.dto.ContainerDetailView;
import com.example.starter.container.dto.ContainerInspectRequest;
import com.example.starter.container.dto.ContainerItemsRequest;
import com.example.starter.container.dto.ContainerReviewRequest;
import com.example.starter.container.dto.PendingVerificationView;
import com.example.starter.evidence.IdempotencyAdvisor;
import com.example.starter.evidence.StoredResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 封存容器巡检 API。操作人通过 X-Actor-Id 请求头提供；
 * 写操作携带 commandKey（巡检即 inspectKey）保证幂等：同键同参重放首次完整结果，
 * 同键改参返回 409，业务失败事务回滚不占用键。集合参数（evidenceKeys）
 * 在计算指纹前去重并按字典序排序，因此换序视为同参。
 */
@RestController
@RequestMapping("/api/containers")
@Validated
public class ContainerController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final ContainerService containerService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public ContainerController(ContainerService containerService,
                               IdempotencyAdvisor idempotencyAdvisor) {
        this.containerService = containerService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 创建封存容器：初始 SEALED，版本 0。
     */
    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @Valid @RequestBody ContainerCreateRequest request) {
        String hash = idempotencyAdvisor.hash(ContainerService.OP_CONTAINER_CREATE, actorId,
                request.containerId(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> containerService.createContainer(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 装载证物：FAIL 容器返回 409。
     */
    @PostMapping("/{containerId}/items")
    public ResponseEntity<String> load(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                       @PathVariable String containerId,
                                       @Valid @RequestBody ContainerItemsRequest request) {
        ContainerItemsRequest canonical = canonicalItems(request);
        String hash = idempotencyAdvisor.hash(ContainerService.OP_CONTAINER_LOAD, actorId,
                containerId, canonical);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> containerService.loadItems(actorId, containerId, canonical, hash));
        return toEntity(response);
    }

    /**
     * 移出证物：FAIL 容器返回 409。
     */
    @PostMapping("/{containerId}/items/unload")
    public ResponseEntity<String> unload(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String containerId,
                                         @Valid @RequestBody ContainerItemsRequest request) {
        ContainerItemsRequest canonical = canonicalItems(request);
        String hash = idempotencyAdvisor.hash(ContainerService.OP_CONTAINER_UNLOAD, actorId,
                containerId, canonical);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> containerService.unloadItems(actorId, containerId, canonical, hash));
        return toEntity(response);
    }

    /**
     * 容器巡检：PASS 更新下次巡检时刻；FAIL 批量转待核验并写逐件快照。
     */
    @PostMapping("/{containerId}/inspections")
    public ResponseEntity<String> inspect(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                          @PathVariable String containerId,
                                          @Valid @RequestBody ContainerInspectRequest request) {
        String hash = idempotencyAdvisor.hash(ContainerService.OP_CONTAINER_INSPECT, actorId,
                containerId, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> containerService.inspect(actorId, containerId, request, hash));
        return toEntity(response);
    }

    /**
     * 复核封签：两名不同保管人完成后容器恢复 SEALED。
     */
    @PostMapping("/{containerId}/reviews")
    public ResponseEntity<String> review(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String containerId,
                                         @Valid @RequestBody ContainerReviewRequest request) {
        String hash = idempotencyAdvisor.hash(ContainerService.OP_CONTAINER_REVIEW, actorId,
                containerId, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> containerService.review(actorId, containerId, request, hash));
        return toEntity(response);
    }

    /**
     * 查询容器巡检详情、逐件快照与复核记录。
     */
    @GetMapping("/{containerId}")
    public ContainerDetailView detail(@PathVariable String containerId) {
        return containerService.containerDetail(containerId);
    }

    /**
     * 查询待核验证物；可按保管人过滤。
     */
    @GetMapping("/pending-verification")
    public List<PendingVerificationView> pendingVerification(
            @RequestParam(name = "custodianId", required = false) String custodianId) {
        return containerService.listPendingVerification(custodianId);
    }

    /**
     * 查询证物借出/迁移阻断原因。
     */
    @GetMapping("/evidence/{evidenceKey}/block-reason")
    public BlockReasonView blockReason(@PathVariable String evidenceKey) {
        return containerService.blockReason(evidenceKey);
    }

    /**
     * 集合参数规范化：去重并按字典序排序，使集合换序计算出相同指纹。
     */
    private ContainerItemsRequest canonicalItems(ContainerItemsRequest request) {
        List<String> canonical = request.evidenceKeys().stream().distinct().sorted().toList();
        return new ContainerItemsRequest(request.commandKey(), canonical);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
