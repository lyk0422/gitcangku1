package com.example.starter.evidence;

import com.example.starter.evidence.dto.ContainerCommandRequest;
import com.example.starter.evidence.dto.ContainerCreateRequest;
import com.example.starter.evidence.dto.ContainerInspectionRequest;
import com.example.starter.evidence.dto.ContainerMemberRequest;
import com.example.starter.evidence.dto.ContainerTransferInitiateRequest;
import com.example.starter.evidence.dto.ContainerTreeView;
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
 * 封存箱 API。操作人通过 X-Actor-Id 请求头提供；写操作携带全局 requestId 保证幂等：
 * 同键同参重放返回首次完整响应，同键改参返回 409，失败不占键。
 * 成员变更（装箱/拆箱）携带根箱 expectedVersion 并加一。
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
     * 新建封存箱：初始为根箱，SEALED，版本 1。
     */
    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @Valid @RequestBody ContainerCreateRequest request) {
        String hash = idempotencyAdvisor.hashStructured(ContainerService.OP_CONTAINER_CREATE,
                actorId, request.containerKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> containerService.create(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 装箱：把证物或子箱挂入目标箱，根箱版本加一。
     */
    @PostMapping("/{containerKey}/members")
    public ResponseEntity<String> pack(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                       @PathVariable String containerKey,
                                       @Valid @RequestBody ContainerMemberRequest request) {
        String hash = idempotencyAdvisor.hashStructured(ContainerService.OP_CONTAINER_PACK,
                actorId, containerKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> containerService.pack(actorId, containerKey, request, hash));
        return toEntity(response);
    }

    /**
     * 拆箱：把证物或子箱从直接父箱取出，根箱版本加一。
     */
    @PostMapping("/{containerKey}/members/remove")
    public ResponseEntity<String> unpack(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String containerKey,
                                         @Valid @RequestBody ContainerMemberRequest request) {
        String hash = idempotencyAdvisor.hashStructured(ContainerService.OP_CONTAINER_UNPACK,
                actorId, containerKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> containerService.unpack(actorId, containerKey, request, hash));
        return toEntity(response);
    }

    /**
     * 根箱发起交接：冻结整树（箱、封条、版本）。
     */
    @PostMapping("/{containerKey}/transfers")
    public ResponseEntity<String> initiateTransfer(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String containerKey,
            @Valid @RequestBody ContainerTransferInitiateRequest request) {
        String hash = idempotencyAdvisor.hashStructured(
                ContainerService.OP_CONTAINER_TRANSFER_INITIATE, actorId, containerKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> containerService.initiateTransfer(actorId, containerKey, request, hash));
        return toEntity(response);
    }

    /**
     * 接受根箱交接：重新核验完整树后一次推进全部保管权。
     */
    @PostMapping("/{containerKey}/transfers/accept")
    public ResponseEntity<String> acceptTransfer(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String containerKey,
            @Valid @RequestBody ContainerCommandRequest request) {
        String hash = idempotencyAdvisor.hashStructured(
                ContainerService.OP_CONTAINER_TRANSFER_ACCEPT, actorId, containerKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> containerService.acceptTransfer(actorId, containerKey, request, hash));
        return toEntity(response);
    }

    /**
     * 取消根箱交接：仅原保管人，整树解冻回到 SEALED。
     */
    @PostMapping("/{containerKey}/transfers/cancel")
    public ResponseEntity<String> cancelTransfer(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String containerKey,
            @Valid @RequestBody ContainerCommandRequest request) {
        String hash = idempotencyAdvisor.hashStructured(
                ContainerService.OP_CONTAINER_TRANSFER_CANCEL, actorId, containerKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> containerService.cancelTransfer(actorId, containerKey, request, hash));
        return toEntity(response);
    }

    /**
     * 箱封条核验：仅当前保管人；失败使该箱进入 SEAL_BROKEN 并使根箱不能交接。
     */
    @PostMapping("/{containerKey}/seal-inspections")
    public ResponseEntity<String> inspectSeal(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String containerKey,
            @Valid @RequestBody ContainerInspectionRequest request) {
        String hash = idempotencyAdvisor.hashStructured(
                ContainerService.OP_CONTAINER_SEAL_INSPECTION, actorId, containerKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> containerService.inspectSeal(actorId, containerKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询自请求箱向下的完整子树；冻结期间仍列出全部成员。
     */
    @GetMapping("/{containerKey}/tree")
    public ContainerTreeView tree(@PathVariable String containerKey) {
        return containerService.tree(containerKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
