package com.example.starter.handoff;

import com.example.starter.batch.StoredResponse;
import com.example.starter.handoff.dto.BatchHolderResponse;
import com.example.starter.handoff.dto.CancelHandoffRequest;
import com.example.starter.handoff.dto.CreateHandoffRequest;
import com.example.starter.handoff.dto.HandoffEvidenceResponse;
import com.example.starter.handoff.dto.ReceiveHandoffRequest;
import com.example.starter.handoff.dto.ShipHandoffRequest;
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

/**
 * 跨厂移交单接口：创建（源厂）、发运（源厂）、接收（目标厂）、取消（源厂），
 * 以及移交证据与当前持有厂只读查询。命令结果沿用 StoredResponse 快照语义。
 */
@RestController
@RequestMapping("/api/handoffs")
public class HandoffController {

    private final HandoffService service;

    public HandoffController(HandoffService service) {
        this.service = service;
    }

    /**
     * 创建移交单并冻结清单；X-Source-Plant 为源厂标识。
     */
    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(name = "X-Source-Plant", required = false) String sourcePlant,
                                         @Valid @RequestBody CreateHandoffRequest request) {
        return stored(service.createHandoff(sourcePlant, request));
    }

    /**
     * 源厂发运：清单全部批次同事务转为 IN_TRANSIT 并写源厂交接快照。
     */
    @PostMapping("/{manifestKey}/ship")
    public ResponseEntity<String> ship(@PathVariable String manifestKey,
                                       @RequestHeader(name = "X-Source-Plant", required = false) String sourcePlant,
                                       @Valid @RequestBody ShipHandoffRequest request) {
        return stored(service.shipHandoff(manifestKey, sourcePlant, request));
    }

    /**
     * 目标厂接收：提交完整 manifest、逐批封签与接收人；X-Target-Plant 为目标厂标识。
     */
    @PostMapping("/{manifestKey}/receive")
    public ResponseEntity<String> receive(@PathVariable String manifestKey,
                                          @RequestHeader(name = "X-Target-Plant", required = false) String targetPlant,
                                          @Valid @RequestBody ReceiveHandoffRequest request) {
        return stored(service.receiveHandoff(manifestKey, targetPlant, request));
    }

    /**
     * 源厂取消：仅尚未接收且清单全部仍在途时允许，原子恢复发运前状态。
     */
    @PostMapping("/{manifestKey}/cancel")
    public ResponseEntity<String> cancel(@PathVariable String manifestKey,
                                         @RequestHeader(name = "X-Source-Plant", required = false) String sourcePlant,
                                         @Valid @RequestBody CancelHandoffRequest request) {
        return stored(service.cancelHandoff(manifestKey, sourcePlant, request));
    }

    /**
     * 移交证据只读查询：概要、冻结清单、两阶段血缘快照与不可变事件，稳定排序。
     */
    @GetMapping("/{manifestKey}/evidence")
    public HandoffEvidenceResponse evidence(@PathVariable String manifestKey) {
        return service.evidence(manifestKey);
    }

    /**
     * 批次当前持有厂只读查询。
     */
    @GetMapping("/batches/{batchKey}/holder")
    public BatchHolderResponse holder(@PathVariable String batchKey) {
        return service.holder(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
