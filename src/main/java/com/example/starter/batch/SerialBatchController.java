package com.example.starter.batch;

import com.example.starter.batch.dto.BatchIntervalsResponse;
import com.example.starter.batch.dto.RegisterSerialBatchRequest;
import com.example.starter.batch.dto.SerialLookupResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 序号区间批次接口：根批登记、受控区间拆批、序号归属与批次序号域查询。
 */
@RestController
@RequestMapping("/api/serial-batches")
public class SerialBatchController {

    private final SerialBatchService service;

    public SerialBatchController(SerialBatchService service) {
        this.service = service;
    }

    /**
     * 登记序号根批：绑定 1～10 个闭区间，区间总长度等于批次数量，初始 QUARANTINED。
     */
    @PostMapping
    public ResponseEntity<String> register(@Valid @RequestBody RegisterSerialBatchRequest request) {
        return stored(service.register(request));
    }

    /**
     * 序号区间拆批：仅当前可用的 RELEASED 序号父批可拆成 2～5 个子批，
     * 子批区间并集必须精确等于父批域且两两不交。
     */
    @PostMapping("/{batchKey}/split")
    public ResponseEntity<String> split(@PathVariable String batchKey,
                                        @Valid @RequestBody SerialSplitRequest request) {
        return stored(service.split(batchKey, request));
    }

    /**
     * 序号归属查询：返回当前持有该序号的叶批及其召回状态。
     */
    @GetMapping("/lookup")
    public SerialLookupResponse lookup(@RequestParam String productCode,
                                       @RequestParam int serial) {
        return service.lookup(productCode, serial);
    }

    /**
     * 批次序号域查询：返回该批冻结区间；已拆分父批的域仅作历史（current=false）。
     */
    @GetMapping("/{batchKey}/intervals")
    public BatchIntervalsResponse intervals(@PathVariable String batchKey) {
        return service.intervals(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
