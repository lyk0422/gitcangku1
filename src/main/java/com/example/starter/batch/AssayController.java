package com.example.starter.batch;

import com.example.starter.batch.dto.AssayRoundDetailResponse;
import com.example.starter.batch.dto.OpenAssayRoundRequest;
import com.example.starter.batch.dto.WriteAssayReadingRequest;
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

import java.util.List;

/**
 * 平行测定接口：轮次开设、读数写入与轮次查询。
 */
@RestController
@RequestMapping("/api/batches/{batchKey}/assay-rounds")
public class AssayController {

    private final AssayService service;

    public AssayController(AssayService service) {
        this.service = service;
    }

    /**
     * 为必需检验项开平行测定轮次。
     */
    @PostMapping
    public ResponseEntity<String> openRound(@PathVariable String batchKey,
                                            @Valid @RequestBody OpenAssayRoundRequest request) {
        return stored(service.openRound(batchKey, request));
    }

    /**
     * 向轮次写入一条读数；第三条读数写入即封结判定。
     */
    @PostMapping("/{roundId}/readings")
    public ResponseEntity<String> writeReading(@PathVariable String batchKey,
                                               @PathVariable long roundId,
                                               @Valid @RequestBody WriteAssayReadingRequest request) {
        return stored(service.writeReading(batchKey, roundId, request));
    }

    /**
     * 查询批次全部测定轮次及原始读数。
     */
    @GetMapping
    public List<AssayRoundDetailResponse> listRounds(@PathVariable String batchKey) {
        return service.listRounds(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
