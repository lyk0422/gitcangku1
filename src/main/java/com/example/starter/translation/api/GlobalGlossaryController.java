package com.example.starter.translation.api;

import com.example.starter.translation.service.GlobalGlossaryService;
import com.example.starter.translation.service.WriteExecutor;
import com.example.starter.translation.service.WriteResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 全局术语库（共享术语库）REST API。
 * 全局术语库不属于任何文档；写操作携带全局唯一 requestId 做幂等去重，
 * 与文档侧写操作共用同一套同参重放、异参 409、失败不占键语义。
 */
@RestController
@RequestMapping("/api/glossary")
public class GlobalGlossaryController {

    private final GlobalGlossaryService globalGlossaryService;
    private final WriteExecutor writeExecutor;
    private final ObjectMapper objectMapper;

    public GlobalGlossaryController(GlobalGlossaryService globalGlossaryService, WriteExecutor writeExecutor,
                                    ObjectMapper objectMapper) {
        this.globalGlossaryService = globalGlossaryService;
        this.writeExecutor = writeExecutor;
        this.objectMapper = objectMapper;
    }

    /** 新增全局术语库版本：不可变快照，全局版本加一；已有版本不可覆盖。 */
    @PutMapping("/terms")
    public ResponseEntity<String> updateGlobalTerms(@Valid @RequestBody ApiDtos.UpdateGlobalTermsRequest request) {
        String operation = "PUT /api/glossary/terms";
        return writeExecutor.execute(request.requestId(), hash(operation, request),
                () -> WriteResult.of(201, globalGlossaryService.updateGlobalTerms(request))).toResponseEntity();
    }

    /** 查询当前全局术语库版本及完整规则集。 */
    @GetMapping("/terms")
    public ResponseEntity<ApiDtos.GlobalTermVersionView> getCurrentGlobalTerms() {
        return ResponseEntity.ok(globalGlossaryService.getCurrentGlobalTerms());
    }

    /** 查询指定全局术语库版本的不可变规则集。 */
    @GetMapping("/terms/{globalTermVersion}")
    public ResponseEntity<ApiDtos.GlobalTermVersionView> getGlobalTerms(@PathVariable int globalTermVersion) {
        return ResponseEntity.ok(globalGlossaryService.getGlobalTerms(globalTermVersion));
    }

    /** 计算请求摘要：操作 + 规范化请求体的 SHA-256。 */
    private String hash(String operation, Object... parts) {
        try {
            StringBuilder canonical = new StringBuilder(operation);
            for (Object part : parts) {
                canonical.append('\n').append(objectMapper.writeValueAsString(part));
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("请求序列化失败", e);
        }
    }
}
