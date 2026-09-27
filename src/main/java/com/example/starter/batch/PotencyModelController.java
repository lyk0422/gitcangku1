package com.example.starter.batch;

import com.example.starter.batch.dto.PotencyModelRequest;
import com.example.starter.batch.dto.PotencyModelResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 效价模型接口：注册新版模型与查询模型版本历史。
 * 放行/拆分/合批读取并固化当时版本，后续修订不改写历史证据。
 */
@RestController
@RequestMapping("/api/potency-models")
public class PotencyModelController {

    private final BatchService service;

    public PotencyModelController(BatchService service) {
        this.service = service;
    }

    /**
     * 注册新版效价模型：版本按当前最大版本 +1 分配。
     */
    @PostMapping
    public ResponseEntity<String> register(@Valid @RequestBody PotencyModelRequest request) {
        StoredResponse response = service.registerModel(request);
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }

    /**
     * 全部效价模型版本，按版本升序；只读不改状态。
     */
    @GetMapping
    public List<PotencyModelResponse> list() {
        return service.listModels();
    }
}
