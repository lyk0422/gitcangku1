package com.example.starter.workblock.service;

import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.support.SystemClock;
import com.example.starter.workblock.model.ActiveWindow;
import com.example.starter.workblock.model.WorkBlock;
import com.example.starter.workblock.model.WorkBlockCancellation;
import com.example.starter.workblock.model.WorkBlockStatus;
import com.example.starter.workblock.repo.SectionRepository;
import com.example.starter.workblock.repo.WorkBlockRepository;
import com.example.starter.workblock.web.dto.AffectedPlanView;
import com.example.starter.workblock.web.dto.CancelWorkBlockRequest;
import com.example.starter.workblock.web.dto.CreateWorkBlockRequest;
import com.example.starter.workblock.web.dto.UpdateWorkBlockRequest;
import com.example.starter.workblock.web.dto.WorkBlockCancellationView;
import com.example.starter.workblock.web.dto.WorkBlockView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 铁路施工占用窗口核心业务：创建/修改/取消、与已发布计划的联合校验及查询。
 *
 * <p>并发与幂等约定：写操作按 (操作类型, requestKey) 幂等，指纹含操作者、expectedVersion、
 * 规范化区段与全部时段字段；同键同参重放返回首次成功响应，同键异参返回 409，失败不占键。
 * 施工单创建/修改/取消与计划发布/改签共用同一全局互斥锁，按事务提交顺序裁决。
 * 窗口区间为 UTC 左闭右开，相交判定 {@code a.start < b.end 且 b.start < a.end}。
 */
@Service
public class WorkBlockService {

    private static final String OP_WORK_CREATE = "WORK_CREATE";
    private static final String OP_WORK_UPDATE = "WORK_UPDATE";
    private static final String OP_WORK_CANCEL = "WORK_CANCEL";

    private final WorkBlockRepository workBlockRepo;
    private final SectionRepository sectionRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final SystemClock clock;
    private final TransactionTemplate tx;

    public WorkBlockService(WorkBlockRepository workBlockRepo, SectionRepository sectionRepo,
                            IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                            SystemClock clock, PlatformTransactionManager txManager) {
        this.workBlockRepo = workBlockRepo;
        this.sectionRepo = sectionRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建施工单：区段必须全部存在、起点早于终点；与生效窗口重叠返回 409 并稳定列出冲突 workKey。
     */
    public WorkBlockView create(CreateWorkBlockRequest request) {
        List<String> sections = normalizeSections(request.sectionIds());
        validateWindowParams(request.startUtc(), request.endUtc(), sections);
        String hash = hashUpsert(OP_WORK_CREATE, request.workKey(), request.operator(),
                null, request.startUtc(), request.endUtc(), sections);
        Optional<WorkBlockView> replay = replayIfPresent(OP_WORK_CREATE, request.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                workBlockRepo.acquireGlobalLock();
                Optional<WorkBlockView> inside = replayIfPresent(
                        OP_WORK_CREATE, request.requestKey(), hash);
                if (inside.isPresent()) {
                    return inside.get();
                }
                if (workBlockRepo.findByKey(request.workKey()).isPresent()) {
                    throw conflict("WORK_KEY_EXISTS", "workKey 已存在: " + request.workKey());
                }
                rejectOverlappingWindows(request.startUtc(), request.endUtc(), sections, -1L);
                long now = clock.nowMillis();
                long id = workBlockRepo.insertWorkBlock(request.workKey(), request.startUtc(),
                        request.endUtc(), request.operator(), now);
                workBlockRepo.replaceSections(id, sections);
                WorkBlockView response = loadView(request.workKey());
                idemRepo.insert(OP_WORK_CREATE, request.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveWriteDuplicate(OP_WORK_CREATE, request.requestKey(), hash,
                    request.workKey());
        }
    }

    /**
     * 修改施工单：expectedVersion 乐观校验，整体替换时段与区段；先做窗口重叠 409 校验，
     * 再以完整后态重校验全部已发布计划，任一冲突 422 且不部分生效。
     */
    public WorkBlockView update(String workKey, UpdateWorkBlockRequest request) {
        List<String> sections = normalizeSections(request.sectionIds());
        validateWindowParams(request.startUtc(), request.endUtc(), sections);
        String hash = hashUpsert(OP_WORK_UPDATE, workKey, request.operator(),
                request.expectedVersion(), request.startUtc(), request.endUtc(), sections);
        Optional<WorkBlockView> replay = replayIfPresent(OP_WORK_UPDATE, request.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                workBlockRepo.acquireGlobalLock();
                Optional<WorkBlockView> inside = replayIfPresent(
                        OP_WORK_UPDATE, request.requestKey(), hash);
                if (inside.isPresent()) {
                    return inside.get();
                }
                WorkBlock block = workBlockRepo.findByKeyForUpdate(workKey)
                        .orElseThrow(() -> notFound(workKey));
                if (block.status() != WorkBlockStatus.ACTIVE) {
                    throw conflict("WORK_BLOCK_STATE_CONFLICT",
                            "仅生效施工单可修改，当前状态: " + block.status());
                }
                if (request.expectedVersion() != block.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + request.expectedVersion()
                                    + " 与当前版本 " + block.version() + " 不一致");
                }
                rejectOverlappingWindows(request.startUtc(), request.endUtc(), sections, block.id());
                rejectPublishedPlanConflicts(workKey, request.startUtc(), request.endUtc(), sections);
                long now = clock.nowMillis();
                workBlockRepo.updateWindow(block.id(), block.version() + 1,
                        request.startUtc(), request.endUtc(), now);
                workBlockRepo.replaceSections(block.id(), sections);
                WorkBlockView response = loadView(workKey);
                idemRepo.insert(OP_WORK_UPDATE, request.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveWriteDuplicate(OP_WORK_UPDATE, request.requestKey(), hash, workKey);
        }
    }

    /**
     * 取消未开始施工单：立即释放占用并追加不可变取消记录；已开始施工单不可取消。
     */
    public WorkBlockView cancel(String workKey, CancelWorkBlockRequest request) {
        String hash = sha256(OP_WORK_CANCEL + '\n' + workKey + '\n' + request.operator());
        Optional<WorkBlockView> replay = replayIfPresent(OP_WORK_CANCEL, request.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                workBlockRepo.acquireGlobalLock();
                Optional<WorkBlockView> inside = replayIfPresent(
                        OP_WORK_CANCEL, request.requestKey(), hash);
                if (inside.isPresent()) {
                    return inside.get();
                }
                WorkBlock block = workBlockRepo.findByKeyForUpdate(workKey)
                        .orElseThrow(() -> notFound(workKey));
                if (block.status() != WorkBlockStatus.ACTIVE) {
                    throw conflict("WORK_BLOCK_STATE_CONFLICT",
                            "仅生效施工单可取消，当前状态: " + block.status());
                }
                long now = clock.nowMillis();
                if (now >= block.startUtc().toEpochMilli()) {
                    throw conflict("WORK_BLOCK_ALREADY_STARTED",
                            "施工单已开始，不可取消: " + workKey);
                }
                workBlockRepo.markCancelled(block.id(), now);
                workBlockRepo.insertCancellation(block.id(), workKey, block.version(),
                        request.operator(), now);
                WorkBlockView response = loadView(workKey);
                idemRepo.insert(OP_WORK_CANCEL, request.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveWriteDuplicate(OP_WORK_CANCEL, request.requestKey(), hash, workKey);
        }
    }

    /**
     * 查询单个施工窗口，不存在返回 404。
     */
    public WorkBlockView get(String workKey) {
        if (workBlockRepo.findByKey(workKey).isEmpty()) {
            throw notFound(workKey);
        }
        return loadView(workKey);
    }

    /**
     * 查询施工窗口列表；includeCancelled 为 false 时仅返回生效窗口。
     */
    public List<WorkBlockView> list(boolean includeCancelled) {
        List<WorkBlockView> views = new ArrayList<>();
        for (WorkBlock block : workBlockRepo.findAll()) {
            if (!includeCancelled && block.status() != WorkBlockStatus.ACTIVE) {
                continue;
            }
            views.add(toView(block, workBlockRepo.findSectionIds(block.id())));
        }
        return views;
    }

    /**
     * 查询指定施工窗口当前相交的已发布计划占用；施工单不存在返回 404。
     */
    public List<AffectedPlanView> getAffectedPlans(String workKey) {
        WorkBlock block = workBlockRepo.findByKey(workKey).orElseThrow(() -> notFound(workKey));
        if (block.status() != WorkBlockStatus.ACTIVE) {
            return List.of();
        }
        List<String> sections = workBlockRepo.findSectionIds(block.id());
        return toAffectedViews(workKey, block.startUtc(), block.endUtc(),
                workBlockRepo.findPublishedOccupancies(block.startUtc(), block.endUtc(), sections));
    }

    /**
     * 查询取消记录；workKey 非空时仅返回该施工单的取消记录，为空返回全部。
     */
    public List<WorkBlockCancellationView> getCancellations(String workKey) {
        List<WorkBlockCancellation> records = workKey == null || workKey.isBlank()
                ? workBlockRepo.findAllCancellations()
                : workBlockRepo.findCancellationByWorkKey(workKey).map(List::of).orElseGet(List::of);
        return records.stream()
                .map(r -> new WorkBlockCancellationView(r.workKey(), r.version(), r.operator(),
                        r.cancelledAt()))
                .toList();
    }

    /**
     * 计划发布/改签联合校验：在调用方事务与全局锁内，检查计划完整后态占用
     * 是否与任一生效施工窗口相交。返回全部冲突（按区段、窗口、开始时刻稳定排序），
     * 调用方取非空即整单回滚，首项即“首个冲突区段和窗口”。
     *
     * @param scheduleKey 被发布/改签计划的业务键，写入冲突明细
     * @param occupancies 计划完整后态占用
     */
    public List<AffectedPlanView> findConflictsWithActiveWindows(String scheduleKey,
                                                                 List<Occupancy> occupancies) {
        List<ActiveWindow> windows = workBlockRepo.findAllActiveWindows();
        List<AffectedPlanView> conflicts = new ArrayList<>();
        for (Occupancy occupancy : occupancies) {
            for (ActiveWindow window : windows) {
                if (!occupancy.sectionId().equals(window.sectionId())) {
                    continue;
                }
                long occStart = occupancy.startUtc().toEpochMilli();
                long occEnd = occupancy.endUtc().toEpochMilli();
                if (occStart < window.endUtc() && window.startUtc() < occEnd) {
                    conflicts.add(new AffectedPlanView(
                            scheduleKey, occupancy.trainNo(), occupancy.sectionId(),
                            occupancy.startUtc(), occupancy.endUtc(),
                            window.workKey(),
                            Instant.ofEpochMilli(window.startUtc()),
                            Instant.ofEpochMilli(window.endUtc())));
                }
            }
        }
        conflicts.sort((a, b) -> {
            int bySection = a.sectionId().compareTo(b.sectionId());
            if (bySection != 0) {
                return bySection;
            }
            int byWorkKey = a.workKey().compareTo(b.workKey());
            if (byWorkKey != 0) {
                return byWorkKey;
            }
            return a.startUtc().compareTo(b.startUtc());
        });
        return conflicts;
    }

    // ---------- 内部实现 ----------

    private void rejectOverlappingWindows(Instant startUtc, Instant endUtc, List<String> sections,
                                          long excludeWorkBlockId) {
        List<ActiveWindow> overlaps = workBlockRepo.findOverlappingActiveWindows(
                sections, startUtc.toEpochMilli(), endUtc.toEpochMilli(), excludeWorkBlockId);
        if (!overlaps.isEmpty()) {
            List<String> conflictingWorkKeys = overlaps.stream()
                    .map(ActiveWindow::workKey)
                    .distinct()
                    .toList();
            List<Map<String, Object>> details = new ArrayList<>();
            for (ActiveWindow window : overlaps) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "WORK_BLOCK_OVERLAP");
                detail.put("workKey", window.workKey());
                detail.put("sectionId", window.sectionId());
                detail.put("windowStart", Instant.ofEpochMilli(window.startUtc()).toString());
                detail.put("windowEnd", Instant.ofEpochMilli(window.endUtc()).toString());
                details.add(detail);
            }
            throw new ApiException(HttpStatus.CONFLICT, "WORK_BLOCK_OVERLAP",
                    "施工窗口时间重叠，冲突 workKey: " + String.join(",", conflictingWorkKeys),
                    details);
        }
    }

    private void rejectPublishedPlanConflicts(String workKey, Instant startUtc, Instant endUtc,
                                              List<String> sections) {
        List<PublishedSlot> conflicts = workBlockRepo.findPublishedOccupancies(
                startUtc, endUtc, sections);
        if (!conflicts.isEmpty()) {
            PublishedSlot first = conflicts.get(0);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("type", "PUBLISHED_PLAN_CONFLICT");
            detail.put("sectionId", first.sectionId());
            detail.put("workKey", workKey);
            detail.put("windowStart", startUtc.toString());
            detail.put("windowEnd", endUtc.toString());
            detail.put("conflictingScheduleKey", first.scheduleKey());
            detail.put("trainNo", first.trainNo());
            detail.put("startUtc", first.startUtc().toString());
            detail.put("endUtc", first.endUtc().toString());
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PUBLISHED_PLAN_CONFLICT",
                    "修改后窗口与已发布计划冲突，首个冲突区段: " + first.sectionId(),
                    List.of(detail));
        }
    }

    private List<AffectedPlanView> toAffectedViews(String workKey, Instant windowStart,
                                                   Instant windowEnd, List<PublishedSlot> slots) {
        return slots.stream()
                .map(s -> new AffectedPlanView(s.scheduleKey(), s.trainNo(), s.sectionId(),
                        s.startUtc(), s.endUtc(), workKey, windowStart, windowEnd))
                .toList();
    }

    private WorkBlockView loadView(String workKey) {
        WorkBlock block = workBlockRepo.findByKey(workKey)
                .orElseThrow(() -> notFound(workKey));
        return toView(block, workBlockRepo.findSectionIds(block.id()));
    }

    private WorkBlockView toView(WorkBlock block, List<String> sections) {
        return new WorkBlockView(block.workKey(), block.version(), block.status().name(),
                block.startUtc(), block.endUtc(), sections, block.operator(), block.cancelledAt());
    }

    /**
     * 区段集合规范化：去重并按字典序排序，集合换序视为同参。
     */
    private List<String> normalizeSections(List<String> sectionIds) {
        return new ArrayList<>(new TreeSet<>(sectionIds));
    }

    /**
     * 参数校验：起点必须早于终点，且所有区段已登记。
     */
    private void validateWindowParams(Instant startUtc, Instant endUtc, List<String> sections) {
        if (!endUtc.isAfter(startUtc)) {
            throw badRequest("施工窗口结束时刻必须晚于开始时刻");
        }
        int existing = sectionRepo.countExisting(sections);
        if (existing != sections.size()) {
            throw badRequest("存在未登记的区段，期望区段数: " + sections.size()
                    + " 已登记: " + existing);
        }
    }

    private Optional<WorkBlockView> replayIfPresent(String opType, String requestKey, String hash) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson());
        });
    }

    /**
     * 并发下唯一键冲突裁决：幂等键已存在则同参重放/异参 409；否则为 workKey 冲突。
     */
    private WorkBlockView resolveWriteDuplicate(String opType, String requestKey, String hash,
                                                String workKey) {
        return replayIfPresent(opType, requestKey, hash)
                .orElseThrow(() -> conflict("WORK_KEY_EXISTS", "workKey 已存在: " + workKey));
    }

    private String hashUpsert(String opType, String workKey, String operator,
                              Integer expectedVersion, Instant startUtc, Instant endUtc,
                              List<String> sections) {
        StringBuilder sb = new StringBuilder(opType).append('\n')
                .append(workKey).append('\n').append(operator).append('\n')
                .append(expectedVersion == null ? "" : expectedVersion).append('\n')
                .append(startUtc.toEpochMilli()).append('\n').append(endUtc.toEpochMilli());
        for (String sectionId : sections) {
            sb.append('\n').append(sectionId);
        }
        return sha256(sb.toString());
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

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private WorkBlockView fromJson(String json) {
        try {
            return objectMapper.readValue(json, WorkBlockView.class);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private ApiException notFound(String workKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "WORK_BLOCK_NOT_FOUND",
                "施工单不存在: " + workKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
