package com.example.starter.batch;

import com.example.starter.batch.dto.CancelHandoffRequest;
import com.example.starter.batch.dto.CreateHandoffRequest;
import com.example.starter.batch.dto.HandoffResponse;
import com.example.starter.batch.dto.ReceiveHandoffRequest;
import com.example.starter.batch.dto.ShipHandoffRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨厂隔离移交接口：创建移交单、发运、接收、取消与移交证据查询。
 */
@RestController
@RequestMapping("/api/handoffs")
public class HandoffController {

    private final HandoffService service;

    public HandoffController(HandoffService service) {
        this.service = service;
    }

    /**
     * 创建移交单：源厂选择 1～50 个本厂持有且非 RELEASED 的批次，冻结清单与祖先血缘快照。
     */
    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody CreateHandoffRequest request) {
        return stored(service.create(request));
    }

    /**
     * 发运：整单批次原子转为 IN_TRANSIT 并写入源厂交接快照（发运前状态与封签号）。
     */
    @PostMapping("/{manifestKey}/ship")
    public ResponseEntity<String> ship(@PathVariable String manifestKey,
                                       @Valid @RequestBody ShipHandoffRequest request) {
        return stored(service.ship(manifestKey, request));
    }

    /**
     * 接收：目标厂提交完整 manifest 与逐批封签号；整单原子切换持有厂并保存不可变接收快照。
     */
    @PostMapping("/{manifestKey}/receive")
    public ResponseEntity<String> receive(@PathVariable String manifestKey,
                                          @Valid @RequestBody ReceiveHandoffRequest request) {
        return stored(service.receive(manifestKey, request));
    }

    /**
     * 取消：仅源厂在尚未接收且清单全部在途时执行，原子恢复发运前状态。
     */
    @PostMapping("/{manifestKey}/cancel")
    public ResponseEntity<String> cancel(@PathVariable String manifestKey,
                                         @Valid @RequestBody CancelHandoffRequest request) {
        return stored(service.cancel(manifestKey, request));
    }

    /**
     * 移交证据查询：只读，清单按冻结顺序稳定排序。
     */
    @GetMapping("/{manifestKey}")
    public HandoffResponse evidence(@PathVariable String manifestKey) {
        return service.evidence(manifestKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
