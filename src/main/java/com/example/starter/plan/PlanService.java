package com.example.starter.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.plan.PlanRepository.IdemRow;
import com.example.starter.plan.PlanRepository.OccupancyRow;
import com.example.starter.plan.PlanRepository.PlanRow;
import com.example.starter.plan.PlanRepository.SlotRow;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 铁路走廊日计划业务服务：草稿创建/整体替换、发布、取消、查询与写操作幂等。
 * 占用区间左闭右开，运营日按 Asia/Shanghai 解释；发布冲突校验在区段-日期行锁内原子完成。
 */
@Service
public class PlanService {

    /** 运营日所属时区。 */
    public static final ZoneId OPERATING_ZONE = ZoneId.of("Asia/Shanghai");

    private final PlanRepository repo;
    private final ObjectMapper objectMapper;

    public PlanService(PlanRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建草稿计划，版本为 1。
     */
    @Transactional
    public PlanResponse create(CreatePlanRequest req) {
        validateOccupancies(req.operatingDate(), req.occupancies());
        String hash = hash("CREATE", canonical(req));
        Optional<PlanResponse> replay = beginIdem(req.requestKey(), "CREATE", hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        long planId;
        try {
            planId = repo.insertPlan(req.scheduleKey(), req.operatingDate());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_SCHEDULE_KEY", "scheduleKey 已存在: " + req.scheduleKey());
        }
        repo.insertOccupancies(planId, req.occupancies());
        PlanResponse response = loadPlan(req.scheduleKey());
        completeIdem(req.requestKey(), response);
        return response;
    }

    /**
     * 按 expectedVersion 整体替换草稿占用清单，成功后版本加一。
     */
    @Transactional
    public PlanResponse update(String scheduleKey, UpdatePlanRequest req) {
        PlanRow plan = repo.findPlanByKey(scheduleKey)
                .orElseThrow(() -> ApiException.notFound("计划不存在: " + scheduleKey));
        String hash = hash("UPDATE", scheduleKey + "|" + canonical(req.expectedVersion(), req.occupancies()));
        Optional<PlanResponse> replay = beginIdem(req.requestKey(), "UPDATE", hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (plan.status() != PlanStatus.DRAFT) {
            throw ApiException.conflict("STATE_CONFLICT", "仅草稿计划可修改，当前状态: " + plan.status());
        }
        if (plan.version() != req.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "版本不匹配: 期望 " + req.expectedVersion() + "，当前 " + plan.version());
        }
        validateOccupancies(plan.operatingDate(), req.occupancies());
        repo.replaceOccupancies(plan.id(), plan.version() + 1, req.occupancies());
        PlanResponse response = loadPlan(scheduleKey);
        completeIdem(req.requestKey(), response);
        return response;
    }

    /**
     * 发布草稿计划：在区段-日期行锁内原子校验计划内列车自冲突与跨计划区段冲突。
     * 任一冲突则整张计划保持草稿并返回 422。
     */
    @Transactional
    public PlanResponse publish(String scheduleKey, KeyedRequest req) {
        PlanRow plan = repo.findPlanByKey(scheduleKey)
                .orElseThrow(() -> ApiException.notFound("计划不存在: " + scheduleKey));
        String hash = hash("PUBLISH", scheduleKey);
        Optional<PlanResponse> replay = beginIdem(req.requestKey(), "PUBLISH", hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (plan.status() != PlanStatus.DRAFT) {
            throw ApiException.conflict("STATE_CONFLICT", "仅草稿计划可发布，当前状态: " + plan.status());
        }
        List<OccupancyRow> occupancies = repo.findOccupancies(plan.id());

        List<ConflictDetail> conflicts = new ArrayList<>(findInternalTrainConflicts(occupancies));

        // 按区段排序依次加锁，避免多区段发布死锁
        TreeSet<String> sectionIds = new TreeSet<>();
        for (OccupancyRow o : occupancies) {
            sectionIds.add(o.sectionId());
        }
        for (String sectionId : sectionIds) {
            repo.ensureSectionDayLock(plan.operatingDate(), sectionId);
            repo.lockSectionDay(plan.operatingDate(), sectionId);
        }
        List<SlotRow> published = repo.findPublishedSlotsOnSections(
                plan.operatingDate(), List.copyOf(sectionIds), plan.id());
        conflicts.addAll(findCrossPlanConflicts(occupancies, published));

        if (!conflicts.isEmpty()) {
            throw ApiException.slotConflict("存在时隙冲突，计划保持草稿", conflicts);
        }
        repo.updateStatus(plan.id(), PlanStatus.PUBLISHED);
        PlanResponse response = loadPlan(scheduleKey);
        completeIdem(req.requestKey(), response);
        return response;
    }

    /**
     * 取消已发布计划：时隙立即释放，历史计划与原始占用保留。
     */
    @Transactional
    public PlanResponse cancel(String scheduleKey, KeyedRequest req) {
        PlanRow plan = repo.findPlanByKey(scheduleKey)
                .orElseThrow(() -> ApiException.notFound("计划不存在: " + scheduleKey));
        String hash = hash("CANCEL", scheduleKey);
        Optional<PlanResponse> replay = beginIdem(req.requestKey(), "CANCEL", hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (plan.status() != PlanStatus.PUBLISHED) {
            throw ApiException.conflict("STATE_CONFLICT", "仅已发布计划可取消，当前状态: " + plan.status());
        }
        repo.updateStatus(plan.id(), PlanStatus.CANCELLED);
        PlanResponse response = loadPlan(scheduleKey);
        completeIdem(req.requestKey(), response);
        return response;
    }

    /**
     * 查询计划明细（含历史与已取消计划的原始占用）。
     */
    @Transactional(readOnly = true)
    public PlanResponse getPlan(String scheduleKey) {
        return loadPlan(scheduleKey);
    }

    /**
     * 查询指定运营日、指定区段当前已发布的时隙。
     */
    @Transactional(readOnly = true)
    public List<SlotView> listPublishedSlots(LocalDate operatingDate, String sectionId) {
        return repo.findPublishedSlots(operatingDate, sectionId).stream()
                .map(s -> new SlotView(s.scheduleKey(), s.trainNo(), s.sectionId(), s.startUtc(), s.endUtc()))
                .toList();
    }

    private PlanResponse loadPlan(String scheduleKey) {
        PlanRow plan = repo.findPlanByKey(scheduleKey)
                .orElseThrow(() -> ApiException.notFound("计划不存在: " + scheduleKey));
        List<OccupancyView> occupancies = repo.findOccupancies(plan.id()).stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
        return new PlanResponse(plan.scheduleKey(), plan.operatingDate(), plan.version(), plan.status(), occupancies);
    }

    /**
     * 校验占用时间规则：结束晚于开始，且起止落在同一运营日（Asia/Shanghai）内；
     * 结束时刻允许恰好为运营日末 24:00（即次日 00:00，右开区间不含该点）。
     */
    private void validateOccupancies(LocalDate operatingDate, List<OccupancyInput> occupancies) {
        Instant dayEnd = operatingDate.plusDays(1).atStartOfDay(OPERATING_ZONE).toInstant();
        for (OccupancyInput o : occupancies) {
            if (!o.endUtc().isAfter(o.startUtc())) {
                throw ApiException.badRequest("占用结束必须晚于开始: 列车 " + o.trainNo() + " 区段 " + o.sectionId());
            }
            LocalDate startDate = o.startUtc().atZone(OPERATING_ZONE).toLocalDate();
            LocalDate endDate = o.endUtc().atZone(OPERATING_ZONE).toLocalDate();
            boolean endInDay = endDate.equals(operatingDate)
                    || (endDate.equals(operatingDate.plusDays(1)) && o.endUtc().equals(dayEnd));
            if (!startDate.equals(operatingDate) || !endInDay) {
                throw ApiException.badRequest("占用必须落在运营日 " + operatingDate + "（Asia/Shanghai）内: 列车 "
                        + o.trainNo() + " 区段 " + o.sectionId());
            }
        }
    }

    /**
     * 计划内同一列车的重叠占用（左闭右开，相邻合法）。
     */
    private List<ConflictDetail> findInternalTrainConflicts(List<OccupancyRow> occupancies) {
        Map<String, List<OccupancyRow>> byTrain = new LinkedHashMap<>();
        for (OccupancyRow o : occupancies) {
            byTrain.computeIfAbsent(o.trainNo(), k -> new ArrayList<>()).add(o);
        }
        List<ConflictDetail> conflicts = new ArrayList<>();
        for (List<OccupancyRow> rows : byTrain.values()) {
            List<OccupancyRow> sorted = rows.stream()
                    .sorted(Comparator.comparing(OccupancyRow::startUtc).thenComparing(OccupancyRow::endUtc))
                    .toList();
            for (int i = 1; i < sorted.size(); i++) {
                OccupancyRow prev = sorted.get(i - 1);
                OccupancyRow curr = sorted.get(i);
                if (curr.startUtc().isBefore(prev.endUtc())) {
                    conflicts.add(new ConflictDetail(curr.sectionId(), curr.trainNo(), null));
                }
            }
        }
        return conflicts;
    }

    /**
     * 本计划占用与其他已发布计划在同日同区段上的重叠（左闭右开，相邻合法）。
     */
    private List<ConflictDetail> findCrossPlanConflicts(List<OccupancyRow> occupancies, List<SlotRow> published) {
        List<ConflictDetail> conflicts = new ArrayList<>();
        for (OccupancyRow o : occupancies) {
            for (SlotRow s : published) {
                if (!o.sectionId().equals(s.sectionId())) {
                    continue;
                }
                if (o.startUtc().isBefore(s.endUtc()) && s.startUtc().isBefore(o.endUtc())) {
                    conflicts.add(new ConflictDetail(o.sectionId(), o.trainNo(), s.scheduleKey()));
                }
            }
        }
        return conflicts;
    }

    /**
     * 写入幂等占位记录；键已存在时校验参数摘要，一致则返回首次结果，不一致抛 409。
     */
    private Optional<PlanResponse> beginIdem(String requestKey, String operation, String requestHash) {
        try {
            repo.insertIdemPending(requestKey, operation, requestHash);
            return Optional.empty();
        } catch (DuplicateKeyException e) {
            IdemRow row = repo.findIdem(requestKey).orElseThrow(() ->
                    ApiException.conflict("IDEMPOTENCY_IN_PROGRESS", "相同 requestKey 的操作正在进行中"));
            if (!row.requestHash().equals(requestHash)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "相同 requestKey 携带了不同的请求参数");
            }
            if (row.responseBody() == null) {
                throw ApiException.conflict("IDEMPOTENCY_IN_PROGRESS", "相同 requestKey 的操作正在进行中");
            }
            try {
                return Optional.of(objectMapper.readValue(row.responseBody(), PlanResponse.class));
            } catch (Exception ex) {
                throw new IllegalStateException("幂等记录响应反序列化失败", ex);
            }
        }
    }

    private void completeIdem(String requestKey, PlanResponse response) {
        try {
            repo.completeIdem(requestKey, 200, objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            throw new IllegalStateException("幂等记录响应序列化失败", e);
        }
    }

    private static String canonical(CreatePlanRequest req) {
        return req.scheduleKey() + "|" + req.operatingDate() + "|" + canonicalOccupancies(req.occupancies());
    }

    private static String canonical(long expectedVersion, List<OccupancyInput> occupancies) {
        return expectedVersion + "|" + canonicalOccupancies(occupancies);
    }

    private static String canonicalOccupancies(List<OccupancyInput> occupancies) {
        StringBuilder sb = new StringBuilder();
        for (OccupancyInput o : occupancies) {
            sb.append(o.trainNo()).append('|').append(o.sectionId()).append('|')
                    .append(o.startUtc()).append('|').append(o.endUtc()).append(';');
        }
        return sb.toString();
    }

    private static String hash(String operation, String canonicalParams) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest((operation + "|" + canonicalParams).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
