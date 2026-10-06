package com.example.starter.batch;

import com.example.starter.batch.dto.CreateSerialBatchRequest;
import com.example.starter.batch.dto.SerialQueryResponse;
import com.example.starter.batch.dto.SerialSplitRequest;
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
 * 序号区间批次接口：新类型根批登记、受控拆批、序号归属查询。
 */
@RestController
@RequestMapping("/api/serial-batches")
public class SerialBatchController {

    private final SerialBatchService service;

    public SerialBatchController(SerialBatchService service) {
        this.service = service;
    }

    /**
     * 登记新类型根批：绑定序号区间域，初始状态 QUARANTINED。
     */
    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody CreateSerialBatchRequest request) {
        return stored(service.createSerialBatch(request));
    }

    /**
     * 受控拆批：为每个子批指定序号区间集合，父批必须为当前可用的 RELEASED 批次。
     */
    @PostMapping("/{batchKey}/split")
    public ResponseEntity<String> split(@PathVariable String batchKey,
                                        @Valid @RequestBody SerialSplitRequest request) {
        return stored(service.splitSerial(batchKey, request));
    }

    /**
     * 序号归属查询：返回该序号当前叶批及召回状态。
     */
    @GetMapping("/{productCode}/serials/{serial}")
    public SerialQueryResponse querySerial(@PathVariable String productCode,
                                           @PathVariable long serial) {
        return service.querySerial(productCode, serial);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
