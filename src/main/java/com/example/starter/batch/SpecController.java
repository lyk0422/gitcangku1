package com.example.starter.batch;

import com.example.starter.batch.dto.CreateSpecRequest;
import com.example.starter.batch.dto.RebindSpecRequest;
import com.example.starter.batch.dto.SubmitMeasurementRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 特性规范接口：规范版本创建（只追加）、批次测量向量检验与规范换版。
 */
@RestController
public class SpecController {

    private final SpecService service;

    public SpecController(SpecService service) {
        this.service = service;
    }

    /**
     * 创建特性规范新版本；版本只追加，同版本重复创建返回 409。
     */
    @PostMapping("/api/specs")
    public ResponseEntity<String> createSpec(@Valid @RequestBody CreateSpecRequest request) {
        return stored(service.createSpec(request));
    }

    /**
     * 提交批次完整测量向量；缺特性或超精度 400，越界记录 FAIL 并 REJECTED。
     */
    @PostMapping("/api/batches/{batchKey}/measurements")
    public ResponseEntity<String> submitMeasurement(@PathVariable String batchKey,
                                                    @Valid @RequestBody SubmitMeasurementRequest request) {
        return stored(service.submitMeasurement(batchKey, request));
    }

    /**
     * 批次规范换版；仅未放行且无任何批准的批次可换，带 expectedVersion 乐观校验。
     */
    @PostMapping("/api/batches/{batchKey}/spec-rebind")
    public ResponseEntity<String> rebind(@PathVariable String batchKey,
                                         @Valid @RequestBody RebindSpecRequest request) {
        return stored(service.rebind(batchKey, request));
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
