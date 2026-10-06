package com.example.starter.plan.service;

import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PowerAudit;
import com.example.starter.plan.model.PowerSlice;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.SectionBinding;
import com.example.starter.plan.model.Substation;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.PowerRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BindSectionRequest;
import com.example.starter.plan.web.dto.CreateSubstationRequest;
import com.example.starter.plan.web.dto.PowerAuditView;
import com.example.starter.plan.web.dto.PowerSliceView;
import com.example.starter.plan.web.dto.SectionBindingResponse;
import com.example.starter.plan.web.dto.SubstationResponse;
import com.example.starter.plan.web.dto.UpdateSubstationRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 变电所供电预算核心：变电所创建/调参、区段绑定、功率时间片校验与裁决快照。
 *
 * <p>并发与幂等约定：写操作以全局 requestId 幂等，同键同参重放返回首次成功结果，
 * 同键不同参（操作、资源、全参数、期望版本任一不同）返回 409；调参、绑定变更与发布/取消
 * 共用发布全局锁共同裁决；每次裁决追加不可变快照，冻结当时绑定、功率切片与上限版本；
 * 仅成功结果写入幂等记录，失败不占键、可修正后重试。
 */
@Service
public class PowerService {

    private final PlanRepository planRepo;
    private final PowerRepository powerRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public PowerService(PlanRepository planRepo, PowerRepository powerRepo,
                        ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.powerRepo = powerRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建变电所（上限版本 1）。
     */
    public SubstationResponse createSubstation(CreateSubstationRequest req) {
        String hash = hashOf("SUB_CREATE", req.substationKey(),
                field("powerCapKw", req.powerCapKw()));
        return idempotent(req.requestId(), hash, SubstationResponse.class, () -> {
            if (powerRepo.findSubstationByKey(req.substationKey()).isPresent()) {
                throw conflict("SUBSTATION_EXISTS", "substationKey 已存在: " + req.substationKey());
            }
            powerRepo.insertSubstation(req.substationKey(), req.powerCapKw(), now());
            return new SubstationResponse(req.substationKey(), req.powerCapKw(), 1);
        }, () -> conflict("SUBSTATION_EXISTS", "substationKey 已存在: " + req.substationKey()));
    }

    /**
     * 调整功率上限：expectedVersion 必须匹配；调低上限使现存已发布计划任一时间片超限则 409，
     * 不生效；成功版本加一并追加快照。
     */
    public SubstationResponse updateCap(String substationKey, UpdateSubstationRequest req) {
        String hash = hashOf("CAP_CHANGE", substationKey,
                field("expectedVersion", req.expectedVersion()),
                field("powerCapKw", req.powerCapKw()));
        return idempotent(req.requestId(), hash, SubstationResponse.class, () -> {
            planRepo.acquirePublishLock();
            Substation sub = powerRepo.findSubstationByKeyForUpdate(substationKey)
                    .orElseThrow(() -> notFoundSub(substationKey));
            if (req.expectedVersion() != sub.version()) {
                throw conflict("VERSION_CONFLICT",
                        "expectedVersion=" + req.expectedVersion()
                                + " 与当前上限版本 " + sub.version() + " 不一致");
            }
            List<Map<String, Object>> exceeding = exceedingSlices(substationKey, req.powerCapKw());
            if (!exceeding.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "POWER_CAP_CONFLICT",
                        "调低上限使现存已发布计划超限，未生效", exceeding);
            }
            powerRepo.updateSubstationCap(sub.id(), req.powerCapKw(), sub.version() + 1, now());
            recordSubstationAudit("CAP_CHANGE", substationKey);
            return new SubstationResponse(substationKey, req.powerCapKw(), sub.version() + 1);
        }, () -> conflict("REQUEST_CONFLICT", "写请求冲突: " + req.requestId()));
    }

    /**
     * 查询变电所，不存在返回 404。
     */
    public SubstationResponse getSubstation(String substationKey) {
        Substation sub = powerRepo.findSubstationByKey(substationKey)
                .orElseThrow(() -> notFoundSub(substationKey));
        return new SubstationResponse(sub.substationKey(), sub.powerCapKw(), sub.version());
    }

    /**
     * 绑定或改绑区段到变电所。首次绑定要求 expectedVersion 为空或 0，且区段无已发布占用
     * （绑定前须取消旧占用）；改绑定要求 expectedVersion 匹配当前绑定版本，
     * 且该区段现存已发布占用迁入目标变电所后不超限，否则 409 不生效。
     */
    public SectionBindingResponse bindSection(String sectionId, BindSectionRequest req) {
        String hash = hashOf("BIND", sectionId,
                field("expectedVersion", req.expectedVersion()),
                field("substationKey", req.substationKey()));
        return idempotent(req.requestId(), hash, SectionBindingResponse.class, () -> {
            planRepo.acquirePublishLock();
            powerRepo.findSubstationByKeyForUpdate(req.substationKey())
                    .orElseThrow(() -> notFoundSub(req.substationKey()));
            Optional<SectionBinding> existing = powerRepo.findBindingForUpdate(sectionId);
            if (existing.isEmpty()) {
                if (req.expectedVersion() != null && req.expectedVersion() != 0) {
                    throw conflict("VERSION_CONFLICT",
                            "区段尚未绑定，expectedVersion 应为空或 0: " + sectionId);
                }
                if (planRepo.hasPublishedOccupancies(sectionId)) {
                    throw conflict("SECTION_HAS_ACTIVE_OCCUPANCIES",
                            "绑定前须取消该区段的已发布占用: " + sectionId);
                }
                powerRepo.insertBinding(sectionId, req.substationKey(), now());
                recordSubstationAudit("BIND", req.substationKey());
                return new SectionBindingResponse(sectionId, req.substationKey(), 1);
            }
            SectionBinding binding = existing.get();
            if (req.expectedVersion() == null || req.expectedVersion() != binding.version()) {
                throw conflict("VERSION_CONFLICT",
                        "expectedVersion=" + req.expectedVersion()
                                + " 与当前绑定版本 " + binding.version() + " 不一致");
            }
            if (!binding.substationKey().equals(req.substationKey())) {
                Substation target = powerRepo.findSubstationByKey(req.substationKey())
                        .orElseThrow(() -> notFoundSub(req.substationKey()));
                List<Map<String, Object>> exceeding = rebindExceedingSlices(sectionId, target);
                if (!exceeding.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "POWER_CAP_CONFLICT",
                            "改绑定使现存已发布计划超限，未生效", exceeding);
                }
            }
            powerRepo.updateBinding(binding.id(), req.substationKey(), binding.version() + 1, now());
            recordSubstationAudit("BIND", req.substationKey());
            if (!binding.substationKey().equals(req.substationKey())) {
                recordSubstationAudit("BIND", binding.substationKey());
            }
            return new SectionBindingResponse(sectionId, req.substationKey(), binding.version() + 1);
        }, () -> conflict("SECTION_BINDING_CONFLICT", "区段绑定冲突: " + sectionId));
    }

    /**
     * 查询区段绑定，未绑定返回 404。
     */
    public SectionBindingResponse getBinding(String sectionId) {
        SectionBinding binding = powerRepo.findBinding(sectionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BINDING_NOT_FOUND",
                        "区段未绑定变电所: " + sectionId));
        return new SectionBindingResponse(binding.sectionId(), binding.substationKey(),
                binding.version());
    }

    /**
     * 查询变电所全部功率裁决快照（按裁决顺序），历史快照不随后续变更漂移。
     */
    public List<PowerAuditView> getAudits(String substationKey) {
        if (powerRepo.findSubstationByKey(substationKey).isEmpty()) {
            throw notFoundSub(substationKey);
        }
        return powerRepo.findAudits(substationKey).stream()
                .map(this::toAuditView)
                .toList();
    }

    // ---------- 内部实现 ----------

    /**
     * 功率预算校验：将候选占用与相关变电所下全部已发布占用的起止合并为恒定区间，
     * 逐片累计功率（同一列车不同占用分别计），任一片超过上限则返回违规明细。
     * 须在发布全局锁内调用；未绑定变电所的区段不参与校验。
     *
     * @param excludePlanIds 校验时排除的计划 id（发布为自身；改签为新旧两个计划）
     */
    public List<Map<String, Object>> validatePowerBudget(LocalDate opDate,
                                                         List<Occupancy> candidates,
                                                         List<Long> excludePlanIds) {
        Map<String, String> bindings = bindingsBySection(candidates.stream()
                .map(Occupancy::sectionId)
                .collect(Collectors.toCollection(TreeSet::new)));
        if (bindings.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> violations = new ArrayList<>();
        for (String substationKey : new TreeSet<>(bindings.values())) {
            Substation sub = powerRepo.findSubstationByKey(substationKey)
                    .orElseThrow(() -> new IllegalStateException("绑定指向缺失变电所: " + substationKey));
            List<Interval> intervals = new ArrayList<>();
            for (PublishedSlot slot : planRepo.findPublishedSlots(opDate,
                    powerRepo.findSectionsBySubstation(substationKey), excludePlanIds)) {
                intervals.add(new Interval(slot.startUtc(), slot.endUtc(), slot.powerKw()));
            }
            for (Occupancy o : candidates) {
                if (substationKey.equals(bindings.get(o.sectionId()))) {
                    intervals.add(new Interval(o.startUtc(), o.endUtc(), o.powerKw()));
                }
            }
            for (PowerSlice slice : mergeSlices(intervals)) {
                if (slice.totalPowerKw() > sub.powerCapKw()) {
                    violations.add(violationDetail(substationKey, sub.powerCapKw(), slice));
                }
            }
        }
        return violations;
    }

    /**
     * 发布/改签/取消裁决后，为计划占用涉及的每个变电所追加功率快照（冻结当时上限版本）。
     */
    public void recordPlanAudit(String opType, LocalDate opDate, Collection<String> sectionIds) {
        Map<String, String> bindings = bindingsBySection(sectionIds);
        for (String substationKey : new TreeSet<>(bindings.values())) {
            Substation sub = powerRepo.findSubstationByKey(substationKey)
                    .orElseThrow(() -> new IllegalStateException("绑定指向缺失变电所: " + substationKey));
            insertAudit(opType, sub, opDate, currentSlices(opDate, substationKey));
        }
    }

    /**
     * 绑定变更/调参裁决后，为目标变电所追加快照：按有在效占用的运营日各一条，无占用记一条空切片。
     */
    private void recordSubstationAudit(String opType, String substationKey) {
        Substation sub = powerRepo.findSubstationByKey(substationKey)
                .orElseThrow(() -> new IllegalStateException("变电所缺失: " + substationKey));
        List<LocalDate> dates = planRepo.findPublishedOpDates(
                powerRepo.findSectionsBySubstation(substationKey));
        if (dates.isEmpty()) {
            insertAudit(opType, sub, null, List.of());
            return;
        }
        for (LocalDate date : dates) {
            insertAudit(opType, sub, date, currentSlices(date, substationKey));
        }
    }

    /**
     * 现存已发布占用在指定上限下的超限时间片（调低上限预检）。
     */
    private List<Map<String, Object>> exceedingSlices(String substationKey, int newCapKw) {
        List<String> sections = powerRepo.findSectionsBySubstation(substationKey);
        List<Map<String, Object>> exceeding = new ArrayList<>();
        for (LocalDate date : planRepo.findPublishedOpDates(sections)) {
            for (PowerSlice slice : currentSlices(date, substationKey)) {
                if (slice.totalPowerKw() > newCapKw) {
                    exceeding.add(exceedingDetail(substationKey, newCapKw, date, slice));
                }
            }
        }
        return exceeding;
    }

    /**
     * 改绑定预检：该区段现存已发布占用迁入目标变电所后的超限时间片。
     */
    private List<Map<String, Object>> rebindExceedingSlices(String sectionId, Substation target) {
        List<Map<String, Object>> exceeding = new ArrayList<>();
        List<String> sections = new ArrayList<>(
                powerRepo.findSectionsBySubstation(target.substationKey()));
        if (!sections.contains(sectionId)) {
            sections.add(sectionId);
        }
        for (LocalDate date : planRepo.findPublishedOpDates(List.of(sectionId))) {
            List<Interval> intervals = new ArrayList<>();
            for (PublishedSlot slot : planRepo.findPublishedSlots(date, sections, List.of(-1L))) {
                intervals.add(new Interval(slot.startUtc(), slot.endUtc(), slot.powerKw()));
            }
            for (PowerSlice slice : mergeSlices(intervals)) {
                if (slice.totalPowerKw() > target.powerCapKw()) {
                    exceeding.add(exceedingDetail(target.substationKey(), target.powerCapKw(),
                            date, slice));
                }
            }
        }
        return exceeding;
    }

    /**
     * 指定运营日某变电所当前已发布占用的恒定功率切片。
     */
    private List<PowerSlice> currentSlices(LocalDate opDate, String substationKey) {
        List<Interval> intervals = new ArrayList<>();
        for (PublishedSlot slot : planRepo.findPublishedSlots(opDate,
                powerRepo.findSectionsBySubstation(substationKey), List.of(-1L))) {
            intervals.add(new Interval(slot.startUtc(), slot.endUtc(), slot.powerKw()));
        }
        return mergeSlices(intervals);
    }

    /**
     * 将全部占用起止点合并为恒定区间并逐片累计功率（精确整数求和，功率为 0 的片不输出）。
     */
    private static List<PowerSlice> mergeSlices(List<Interval> intervals) {
        TreeSet<Instant> points = new TreeSet<>();
        for (Interval i : intervals) {
            points.add(i.start());
            points.add(i.end());
        }
        List<PowerSlice> slices = new ArrayList<>();
        Instant prev = null;
        for (Instant point : points) {
            if (prev != null && prev.isBefore(point)) {
                long total = 0;
                for (Interval i : intervals) {
                    if (!i.start().isAfter(prev) && !i.end().isBefore(point)) {
                        total += i.powerKw();
                    }
                }
                if (total > 0) {
                    slices.add(new PowerSlice(prev, point, total));
                }
            }
            prev = point;
        }
        return slices;
    }

    private Map<String, String> bindingsBySection(Collection<String> sectionIds) {
        Map<String, String> bindings = new LinkedHashMap<>();
        for (SectionBinding b : powerRepo.findBindings(sectionIds)) {
            bindings.put(b.sectionId(), b.substationKey());
        }
        return bindings;
    }

    private void insertAudit(String opType, Substation sub, LocalDate opDate,
                             List<PowerSlice> slices) {
        powerRepo.insertAudit(opType, sub.substationKey(), opDate, sub.version(), sub.powerCapKw(),
                toJson(slices.stream()
                        .map(s -> new PowerSliceView(s.startUtc(), s.endUtc(), s.totalPowerKw()))
                        .toList()),
                now());
    }

    private PowerAuditView toAuditView(PowerAudit audit) {
        return new PowerAuditView(audit.id(), audit.opType(), audit.substationKey(), audit.opDate(),
                audit.capVersion(), audit.capKw(), parseSlices(audit.slicesJson()),
                audit.createdAt());
    }

    private List<PowerSliceView> parseSlices(String slicesJson) {
        try {
            return objectMapper.readValue(slicesJson, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("功率切片快照反序列化失败", e);
        }
    }

    private Map<String, Object> violationDetail(String substationKey, int capKw, PowerSlice slice) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", "POWER_BUDGET_EXCEEDED");
        detail.put("substationKey", substationKey);
        detail.put("startUtc", slice.startUtc().toString());
        detail.put("endUtc", slice.endUtc().toString());
        detail.put("actualPowerKw", slice.totalPowerKw());
        detail.put("capKw", capKw);
        return detail;
    }

    private Map<String, Object> exceedingDetail(String substationKey, int capKw, LocalDate opDate,
                                                PowerSlice slice) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", "POWER_CAP_EXCEEDED");
        detail.put("substationKey", substationKey);
        detail.put("opDate", opDate.toString());
        detail.put("startUtc", slice.startUtc().toString());
        detail.put("endUtc", slice.endUtc().toString());
        detail.put("actualPowerKw", slice.totalPowerKw());
        detail.put("capKw", capKw);
        return detail;
    }

    /**
     * 全局 requestId 幂等：同键同参重放首次结果，同键不同参 409；业务与去重记录同事务提交；
     * 并发唯一键冲突时不向外暴露，按已提交记录裁决。
     */
    private <T> T idempotent(String requestId, String hash, Class<T> type,
                             Supplier<T> action, Supplier<ApiException> duplicateFallback) {
        Optional<T> replay = replay(requestId, hash, type);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                T response = action.get();
                powerRepo.insertRequestRecord(requestId, hash, toJson(response), now());
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replay(requestId, hash, type).orElseThrow(duplicateFallback);
        } catch (ApiException e) {
            // 并发下同键请求可能在本事务进入后才由获胜方提交：按已提交记录再裁决一次
            return replay(requestId, hash, type).orElseThrow(() -> e);
        }
    }

    private <T> Optional<T> replay(String requestId, String hash, Class<T> type) {
        return powerRepo.findRequestRecord(requestId).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("REQUEST_ID_REUSED",
                        "requestId 已用于其他操作或参数: " + requestId);
            }
            return fromJson(record.responseJson(), type);
        });
    }

    /**
     * 结构化参数哈希：操作、资源与全参数逐字段长度前缀拼接，字段间无碰撞；
     * 文本原样、整数按十进制规范值参与。
     */
    private String hashOf(String op, String resource, String... fields) {
        StringBuilder sb = new StringBuilder("POWER\n").append(op).append('\n').append(resource);
        for (String f : fields) {
            sb.append('\n').append(f);
        }
        return sha256(sb.toString());
    }

    private static String field(String name, Object value) {
        String text = String.valueOf(value);
        return name + '=' + text.length() + ':' + text;
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private ApiException notFoundSub(String substationKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "SUBSTATION_NOT_FOUND",
                "变电所不存在: " + substationKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /**
     * 功率累计用占用区间，区间左闭右开。
     */
    private record Interval(Instant start, Instant end, long powerKw) {
    }
}
