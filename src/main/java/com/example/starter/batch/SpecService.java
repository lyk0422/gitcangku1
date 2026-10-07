package com.example.starter.batch;

import com.example.starter.batch.dto.AppendSpecRequest;
import com.example.starter.batch.dto.CreateSpecRequest;
import com.example.starter.batch.dto.SpecVersionResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 耦合公差规范服务：规范只追加新版本，已发布版本不可修改。
 * 创建首版与追加版本均走全局 requestId 幂等；并发追加靠 (spec_key, version) 唯一约束裁决。
 */
@Service
public class SpecService {

    /**
     * 全局幂等命令类型：新增写请求共用同一 requestId 命名空间，跨操作唯一。
     */
    public static final String CMD_GLOBAL = "GLOBAL";

    private final SpecRepository specRepo;
    private final IdempotentExecutor idem;
    private final ObjectMapper objectMapper;

    public SpecService(SpecRepository specRepo, IdempotentExecutor idem, ObjectMapper objectMapper) {
        this.specRepo = specRepo;
        this.idem = idem;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建规范首版（version=1）；specKey 已存在返回 409。
     */
    public StoredResponse createInitial(CreateSpecRequest req) {
        SpecDefinition def = SpecDefinition.of(req.features(), req.constraints());
        String fingerprint = IdempotentExecutor.fingerprint(
                concat("spec-create", req.specKey(), def));
        return idem.execute(CMD_GLOBAL, req.requestId(), fingerprint, () -> {
            if (specRepo.findMaxVersion(req.specKey()).isPresent()) {
                throw ApiException.conflict("specKey 已存在，请使用追加版本: " + req.specKey());
            }
            String now = Instant.now().toString();
            specRepo.insertSpec(new SpecRepository.SpecRow(0L, req.specKey(), 1,
                    def.featuresJson(objectMapper), def.constraintsJson(objectMapper),
                    req.operator(), req.requestId(), now));
            return new StoredResponse(201, toJson(toResponse(req.specKey(), 1, def,
                    req.operator(), now)));
        });
    }

    /**
     * 追加新版本：版本号为当前最大版本 +1；规范不存在返回 404。
     */
    public StoredResponse appendVersion(String specKey, AppendSpecRequest req) {
        SpecDefinition def = SpecDefinition.of(req.features(), req.constraints());
        String fingerprint = IdempotentExecutor.fingerprint(
                concat("spec-append", specKey, def));
        return idem.execute(CMD_GLOBAL, req.requestId(), fingerprint, () -> {
            int max = specRepo.findMaxVersion(specKey)
                    .orElseThrow(() -> ApiException.notFound("规范不存在: " + specKey));
            int version = max + 1;
            String now = Instant.now().toString();
            specRepo.insertSpec(new SpecRepository.SpecRow(0L, specKey, version,
                    def.featuresJson(objectMapper), def.constraintsJson(objectMapper),
                    req.operator(), req.requestId(), now));
            return new StoredResponse(201, toJson(toResponse(specKey, version, def,
                    req.operator(), now)));
        });
    }

    /**
     * 查询指定版本完整定义；不存在返回 404。
     */
    public SpecVersionResponse getVersion(String specKey, int version) {
        SpecRepository.SpecRow row = specRepo.findSpec(specKey, version)
                .orElseThrow(() -> ApiException.notFound(
                        "规范版本不存在: " + specKey + "@" + version));
        SpecDefinition def = SpecDefinition.fromJson(row.featuresJson(), row.constraintsJson(),
                objectMapper);
        return toResponse(row.specKey(), row.version(), def, row.operator(), row.createdAt());
    }

    private SpecVersionResponse toResponse(String specKey, int version, SpecDefinition def,
                                           String operator, String createdAt) {
        return new SpecVersionResponse(specKey, version, def.features(),
                def.constraints().stream()
                        .map(c -> new com.example.starter.batch.dto.SpecConstraintInput(
                                c.coefficients(), c.bound()))
                        .toList(),
                operator, Instant.parse(createdAt));
    }

    private static String[] concat(String operation, String specKey, SpecDefinition def) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        parts.add(operation);
        parts.add(specKey);
        parts.addAll(def.canonicalParts());
        return parts.toArray(new String[0]);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
