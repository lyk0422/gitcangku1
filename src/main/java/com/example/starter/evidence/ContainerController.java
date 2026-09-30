package com.example.starter.evidence;

import com.example.starter.evidence.dto.ContainerCreateRequest;
import com.example.starter.evidence.dto.ContainerDetailView;
import com.example.starter.evidence.dto.ContainerInspectionRequest;
import com.example.starter.evidence.dto.ContainerPlaceRequest;
import com.example.starter.evidence.dto.ContainerReviewRequest;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 容器巡检 API。操作人通过 X-Actor-Id 请求头提供；
 * 创建/放入/移出/复核携带 commandKey 保证幂等；巡检确认以 requestId 为幂等键，
 * 两名不同保管人确认同一容器版本后生效，同键重放返回首次结果（含首次隔离清单）。
 */
@RestController
@RequestMapping("/api/containers")
@Validated
public class ContainerController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final ContainerService containerService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public ContainerController(ContainerService containerService, IdempotencyAdvisor idempotencyAdvisor) {
        this.containerService = containerService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 创建容器：初始版本 0、状态 NORMAL。
     */
    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @Valid @RequestBody ContainerCreateRequest request) {
        String hash = idempotencyAdvisor.hash(ContainerService.OP_CONTAINER_CREATE, actorId,
                request.containerKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> containerService.create(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 证物放入容器（含容器间转移）：仅当前保管人，证物须 SEALED；源与目标容器版本自增。
     */
    @PostMapping("/{containerKey}/items")
    public ResponseEntity<String> place(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                        @PathVariable String containerKey,
                                        @Valid @RequestBody ContainerPlaceRequest request) {
        String hash = idempotencyAdvisor.hash(ContainerService.OP_CONTAINER_PLACE, actorId,
                containerKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> containerService.place(actorId, containerKey, request, hash));
        return toEntity(response);
    }

    /**
     * 证物移出容器：仅当前保管人；异常容器复核前禁止移出。
     */
    @PostMapping("/{containerKey}/removals")
    public ResponseEntity<String> remove(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String containerKey,
                                         @Valid @RequestBody ContainerPlaceRequest request) {
        String hash = idempotencyAdvisor.hash(ContainerService.OP_CONTAINER_REMOVE, actorId,
                containerKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> containerService.remove(actorId, containerKey, request, hash));
        return toEntity(response);
    }

    /**
     * 容器巡检确认：两名不同保管人以同一 requestId 确认同一容器版本；
     * 封条异常且双人确认后，当时箱内全部证物一次转为隔离待复核。
     */
    @PostMapping("/{containerKey}/inspections")
    public ResponseEntity<String> inspect(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                          @PathVariable String containerKey,
                                          @Valid @RequestBody ContainerInspectionRequest request) {
        StoredResponse response = containerService.submitInspection(actorId, containerKey, request);
        return toEntity(response);
    }

    /**
     * 复核放行：异常容器放行后回到 NORMAL，箱内隔离证物回到 SEALED；历史保留。
     */
    @PostMapping("/{containerKey}/review")
    public ResponseEntity<String> review(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String containerKey,
                                         @Valid @RequestBody ContainerReviewRequest request) {
        String hash = idempotencyAdvisor.hash(ContainerService.OP_CONTAINER_REVIEW, actorId,
                containerKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> containerService.review(actorId, containerKey, request, hash));
        return toEntity(response);
    }

    /**
     * 容器详情：当前状态与箱内清单 + 全部巡检记录（巡检人、封条结果、当时箱内清单及每件隔离状态）。
     */
    @GetMapping("/{containerKey}")
    public ContainerDetailView detail(@PathVariable String containerKey) {
        return containerService.detail(containerKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
