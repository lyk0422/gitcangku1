package com.example.starter.evidence;

import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.LocationCreateRequest;
import com.example.starter.evidence.dto.LocationInventoryView;
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
 * 库位 API：创建、停用与库存查询。操作人通过 X-Actor-Id 请求头提供；
 * 写操作携带 commandKey 保证幂等：同键同参重放返回首次结果，同键改参返回 409。
 */
@RestController
@RequestMapping("/api/locations")
@Validated
public class LocationController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final LocationService locationService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public LocationController(LocationService locationService, IdempotencyAdvisor idempotencyAdvisor) {
        this.locationService = locationService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 创建库位：locationCode 全局唯一，初始 ACTIVE、库存版本 0。
     */
    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @Valid @RequestBody LocationCreateRequest request) {
        String hash = idempotencyAdvisor.hash(LocationService.OP_LOCATION_CREATE, actorId,
                request.locationCode(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> locationService.create(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 停用库位：仅 ACTIVE 可停用；停用后不可作为入库或迁移目标。
     */
    @PostMapping("/{locationCode}/disable")
    public ResponseEntity<String> disable(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                          @PathVariable String locationCode,
                                          @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(LocationService.OP_LOCATION_DISABLE, actorId,
                locationCode, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> locationService.disable(actorId, locationCode, request, hash));
        return toEntity(response);
    }

    /**
     * 查询库位库存：库位状态、库存版本与库内全部证物。
     */
    @GetMapping("/{locationCode}/inventory")
    public LocationInventoryView inventory(@PathVariable String locationCode) {
        return locationService.inventory(locationCode);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
