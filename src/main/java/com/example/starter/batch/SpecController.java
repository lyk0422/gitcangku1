package com.example.starter.batch;

import com.example.starter.batch.dto.AppendSpecRequest;
import com.example.starter.batch.dto.CreateSpecRequest;
import com.example.starter.batch.dto.SpecVersionResponse;
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
 * 耦合公差规范接口：创建首版、追加新版本、查询指定版本。规范只追加不修改。
 */
@RestController
@RequestMapping("/api/specs")
public class SpecController {

    private final SpecService service;

    public SpecController(SpecService service) {
        this.service = service;
    }

    /**
     * 创建规范首版（version=1）。
     */
    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody CreateSpecRequest request) {
        return stored(service.createInitial(request));
    }

    /**
     * 追加规范新版本，版本号为当前最大版本 +1。
     */
    @PostMapping("/{specKey}/versions")
    public ResponseEntity<String> append(@PathVariable String specKey,
                                         @Valid @RequestBody AppendSpecRequest request) {
        return stored(service.appendVersion(specKey, request));
    }

    /**
     * 查询指定版本的完整规范定义。
     */
    @GetMapping("/{specKey}/versions/{version}")
    public SpecVersionResponse get(@PathVariable String specKey, @PathVariable int version) {
        return service.getVersion(specKey, version);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
