package com.example.starter.water;

import com.example.starter.water.WaterRepository.FieldRow;
import com.example.starter.water.WaterRepository.RequestLogRow;
import com.example.starter.water.dto.Dtos.FieldResponse;
import com.example.starter.water.dto.Dtos.InfiltrationEventView;
import com.example.starter.water.dto.Dtos.InfiltrationResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 田块入渗服务。
 *
 * <p>创建田块：首次关联合成农户并设 UTC 启用时刻，容量 C 与每小时恢复 R 为正整数、创建后不可变，
 * 初始入渗余额等于 C。新增写入使用 requestId 幂等：身份含操作、操作者、资源及全部参数和期望版本，
 * 参数以 JSON 结构化存储并结构比较（避免分隔符碰撞）；同键同参重放首次完整响应，异参 409，
 * 业务失败事务回滚不占键，并发同键由主键唯一约束裁决，不泄露唯一键异常。</p>
 *
 * <p>现况查询：按当前已批准申请的最新时序重算入渗余额，只读不写，不改写批准时冻结的历史余额。</p>
 */
@Service
public class FieldService {

    private final WaterRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public FieldService(WaterRepository repository, PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /** 创建田块；expectedVersion 仅允许为 0（田块尚未创建），可空。 */
    public FieldResponse createField(String requestId, String fieldKey, Long capacity, Long recoveryPerHour,
                                     String enabledAtUtc, Long expectedVersion, String actor) {
        WaterService.requireKey("requestId", requestId);
        WaterService.requireKey("fieldKey", fieldKey);
        WaterService.requireKey("X-Actor-Id", actor);
        if (capacity == null || capacity <= 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "capacity 必须为正整数（立方米）");
        }
        if (recoveryPerHour == null || recoveryPerHour <= 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "recoveryPerHour 必须为正整数（立方米/小时）");
        }
        long enabledNanos = WaterService.parseInstant("enabledAtUtc", enabledAtUtc);
        if (expectedVersion != null && expectedVersion != 0) {
            throw ApiException.conflict("VERSION_CONFLICT", "田块尚未创建，期望版本须为 0");
        }
        ObjectNode params = objectMapper.createObjectNode();
        params.put("capacity", capacity);
        params.put("recoveryPerHour", recoveryPerHour);
        params.put("enabledNanos", enabledNanos);
        params.put("expectedVersion", expectedVersion == null ? 0 : expectedVersion);
        return runRequest("FIELD_CREATE", requestId, actor, fieldKey, params, FieldResponse.class, () -> {
            if (repository.findFieldByKey(fieldKey) != null) {
                throw ApiException.conflict("FIELD_EXISTS", "田块已存在: " + fieldKey);
            }
            String farmer = "farmer-" + UUID.randomUUID().toString().substring(0, 8);
            long now = WaterService.nowNanos();
            repository.insertField(fieldKey, farmer, capacity, recoveryPerHour, enabledNanos, now);
            return toFieldResponse(repository.findFieldByKey(fieldKey));
        });
    }

    /** 现况查询：按最新时序重算入渗余额，只读不写。 */
    public InfiltrationResponse getInfiltration(String fieldKey) {
        return tx.execute(status -> {
            FieldRow field = repository.findFieldByKey(fieldKey);
            if (field == null) {
                throw ApiException.notFound("FIELD_NOT_FOUND", "田块不存在: " + fieldKey);
            }
            List<InfiltrationEngine.Event> events = new ArrayList<>();
            for (var row : repository.listApprovedByField(fieldKey)) {
                events.add(new InfiltrationEngine.Event(row.allocationKey(), row.irrigationNanos(),
                        row.heldAmount()));
            }
            InfiltrationEngine.Outcome outcome = InfiltrationEngine.evaluate(field.capacity(),
                    field.recoveryPerHour(), field.enabledNanos(), events);
            List<InfiltrationEventView> views = outcome.steps().stream()
                    .map(step -> new InfiltrationEventView(step.allocationKey(),
                            WaterService.toIso(step.irrigationNanos()), WaterService.fmt(step.deduct()),
                            WaterService.fmt(step.balanceAfter())))
                    .toList();
            return new InfiltrationResponse(field.fieldKey(), field.capacity(), field.recoveryPerHour(),
                    WaterService.toIso(field.enabledNanos()), views,
                    WaterService.fmt(outcome.finalBalance(field.capacity())));
        });
    }

    // ------------------------------------------------------------------
    // requestId 幂等框架
    // ------------------------------------------------------------------

    /**
     * 在单事务内执行幂等新增写入：先占位插入日志行，再执行业务并写回响应。
     * 并发同键时占位插入因主键唯一约束失败，随后读取已提交的首次结果重放或报 409。
     */
    private <T> T runRequest(String operation, String requestId, String actor, String resource,
                             JsonNode params, Class<T> type, Supplier<T> business) {
        try {
            return tx.execute(status -> {
                RequestLogRow existing = repository.findRequest(requestId);
                if (existing != null) {
                    return replay(existing, operation, actor, resource, params, type);
                }
                repository.insertRequest(requestId, operation, actor, resource, params.toString(),
                        WaterService.nowNanos());
                T result = business.get();
                repository.updateRequestResponse(requestId, toJson(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            RequestLogRow committed = repository.findRequest(requestId);
            if (committed == null || committed.response() == null) {
                throw ApiException.conflict("REQUEST_CONFLICT", "相同 requestId 的写入正在处理，请重试");
            }
            return replay(committed, operation, actor, resource, params, type);
        }
    }

    /** 结构化比较幂等身份：操作、操作者、资源逐项相等，参数按 JSON 结构相等（与键序、分隔符无关）。 */
    private <T> T replay(RequestLogRow existing, String operation, String actor, String resource,
                         JsonNode params, Class<T> type) {
        if (!existing.operation().equals(operation) || !existing.actor().equals(actor)
                || !existing.resource().equals(resource)
                || !jsonStructurallyEquals(readTree(existing.paramsJson()), params)) {
            throw ApiException.conflict("REQUEST_KEY_REUSED", "相同 requestId 但参数不同，拒绝重放");
        }
        return fromJson(existing.response(), type);
    }

    /** 递归结构化比较：数值按十进制值比较（与 IntNode/LongNode 表示无关），对象按键集合递归。 */
    private boolean jsonStructurallyEquals(JsonNode a, JsonNode b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.getNodeType() != b.getNodeType()) {
            return false;
        }
        if (a.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            Iterator<String> names = a.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!jsonStructurallyEquals(a.get(name), b.get(name))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!jsonStructurallyEquals(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private FieldResponse toFieldResponse(FieldRow row) {
        return new FieldResponse(row.fieldKey(), row.farmer(), row.capacity(), row.recoveryPerHour(),
                WaterService.toIso(row.enabledNanos()), row.version(), WaterService.toIso(row.createdNanos()));
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("幂等参数解析失败", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("响应反序列化失败", e);
        }
    }
}
