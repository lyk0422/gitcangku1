package com.example.starter.translation.api;

import com.example.starter.translation.service.TranslationService;
import com.example.starter.translation.service.WriteExecutor;
import com.example.starter.translation.service.WriteResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 共享全局术语库 REST API。
 * 全局术语库不属于任何文档：版本从 1 起递增，每版为不可变快照（0~200 条按 sourceTerm 与目标语言唯一的规则），
 * 提交新版本携带 expectedGlobalTermVersion 与完整规则集，已有版本不可覆盖；其更新不改变任何文档的 draftVersion。
 */
@RestController
@RequestMapping("/api/global-terms")
public class GlobalTermController {

    private final TranslationService translationService;
    private final WriteExecutor writeExecutor;
    private final ObjectMapper objectMapper;

    public GlobalTermController(TranslationService translationService, WriteExecutor writeExecutor,
                                ObjectMapper objectMapper) {
        this.translationService = translationService;
        this.writeExecutor = writeExecutor;
        this.objectMapper = objectMapper;
    }

    /** 提交全局术语库新版本：期望版本不符 409，规则重复或含 suppressed 422。 */
    @PostMapping
    public ResponseEntity<String> updateGlobalTerms(@Valid @RequestBody ApiDtos.UpdateGlobalTermsRequest request) {
        return writeExecutor.execute(request.requestId(), hash("POST /api/global-terms", request),
                () -> WriteResult.of(201, translationService.updateGlobalTerms(request))).toResponseEntity();
    }

    /** 查询全局术语库当前版本及完整规则集。 */
    @GetMapping
    public ResponseEntity<ApiDtos.GlobalTermVersionView> getCurrentGlobalTerms() {
        return ResponseEntity.ok(translationService.getCurrentGlobalTerms());
    }

    /** 查询指定全局术语库版本的不可变规则集。 */
    @GetMapping("/{globalTermVersion}")
    public ResponseEntity<ApiDtos.GlobalTermVersionView> getGlobalTerms(@PathVariable int globalTermVersion) {
        return ResponseEntity.ok(translationService.getGlobalTerms(globalTermVersion));
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
