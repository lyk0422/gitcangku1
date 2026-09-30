package com.example.starter.playout;

import com.example.starter.playout.PlayoutRepository.AssetRow;
import com.example.starter.playout.PlayoutRepository.RequestRow;
import com.example.starter.playout.RegionPlayoutRepository.RegionAllocationRow;
import com.example.starter.playout.RegionPlayoutRepository.RegionGrantRow;
import com.example.starter.playout.RegionPlayoutRepository.RegionRow;
import com.example.starter.playout.api.ApiException;
import com.example.starter.playout.api.Dtos.CreateRegionGrantRequest;
import com.example.starter.playout.api.Dtos.CreateRegionRequest;
import com.example.starter.playout.api.Dtos.RegionAllocationResponse;
import com.example.starter.playout.api.Dtos.RegionConflictResponse;
import com.example.starter.playout.api.Dtos.RegionGrantResponse;
import com.example.starter.playout.api.Dtos.RegionOccupancyResponse;
import com.example.starter.playout.api.Dtos.RegionResponse;
import com.example.starter.playout.api.Dtos.RegionScheduleResponse;
import com.example.starter.playout.api.Dtos.RegionSegmentInput;
import com.example.starter.playout.api.Dtos.SubmitRegionScheduleRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 区域切换编排服务：区域、区域授权与区域时段占位。
 *
 * <p>约定：数据库内时间统一为 UTC 纪元毫秒；API 边界使用带偏移的 ISO 8601，授权窗口与播出时段
 * 均为 UTC 语义（左闭右开）。编排员一次可把素材分配给多个区域的不同时段，授权服务（以区域授权
 * 表为依据）确认每段均落在该区域授权窗口内；同一区域时段按左闭右开判重，相邻端点可接续。
 * 任一区域授权失效或时段重叠，整次 422 回滚，不留下部分区域占位。区域授权变化（撤销）只影响
 * 之后的编排，历史占位固化素材与授权版本（grant_id 及授权窗口快照），不追改。所有写操作与
 * 幂等去重记录在同一事务提交，失败整体回滚、不占用 requestId。</p>
 */
@Service
public class RegionPlayoutService {

    private static final String OP_REVOKE_REGION_GRANT = "REGION_REVOKE_GRANT";
    private static final String OP_SUBMIT_REGION_SCHEDULE = "REGION_SUBMIT_SCHEDULE";

    private final RegionPlayoutRepository regionRepo;
    private final PlayoutRepository requestRepo;
    private final ObjectMapper objectMapper;

    public RegionPlayoutService(RegionPlayoutRepository regionRepo, PlayoutRepository requestRepo,
                                ObjectMapper objectMapper) {
        this.regionRepo = regionRepo;
        this.requestRepo = requestRepo;
        this.objectMapper = objectMapper;
    }

    // ---------- 区域 ----------

    /** 创建区域；id 为空时生成稳定 ID。 */
    @Transactional
    public RegionResponse createRegion(CreateRegionRequest request) {
        String id = request.id() == null || request.id().isBlank()
                ? UUID.randomUUID().toString() : request.id();
        long createdAtMs = nowMs();
        try {
            regionRepo.insertRegion(id, createdAtMs);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_ID", "区域 ID 已存在: " + id);
        }
        return new RegionResponse(id, atMs(createdAtMs));
    }

    // ---------- 区域授权 ----------

    /** 创建区域授权，窗口 [validFrom, validTo) 左闭右开。 */
    @Transactional
    public RegionGrantResponse createRegionGrant(CreateRegionGrantRequest request) {
        regionRepo.findRegion(request.regionId())
                .orElseThrow(() -> ApiException.notFound("区域不存在: " + request.regionId()));
        if (requestRepo.findAsset(request.assetId()).isEmpty()) {
            throw ApiException.notFound("素材不存在: " + request.assetId());
        }
        long fromMs = toMs(request.validFrom());
        long toMs = toMs(request.validTo());
        if (toMs <= fromMs) {
            throw ApiException.badRequest("区域授权有效区间终点必须大于起点");
        }
        long id = regionRepo.insertRegionGrant(request.regionId(), request.assetId(),
                fromMs, toMs, nowMs());
        return new RegionGrantResponse(id, request.regionId(), request.assetId(),
                request.validFrom(), request.validTo(), false);
    }

    /** 撤销区域授权；幂等：同 requestId 同参数返回原结果，改参数返回 409。只影响之后的编排。 */
    @Transactional
    public RegionGrantResponse revokeRegionGrant(long grantId, String requestId) {
        String hash = sha256(OP_REVOKE_REGION_GRANT + "|" + grantId);
        return idempotent(requestId, OP_REVOKE_REGION_GRANT, hash, RegionGrantResponse.class, () -> {
            RegionGrantRow grant = regionRepo.findRegionGrant(grantId)
                    .orElseThrow(() -> ApiException.notFound("区域授权不存在: " + grantId));
            int updated = regionRepo.revokeRegionGrant(grantId, requestId, nowMs());
            if (updated == 0) {
                throw ApiException.conflict("REGION_GRANT_ALREADY_REVOKED",
                        "区域授权已撤销: " + grantId);
            }
            return new RegionGrantResponse(grant.id(), grant.regionId(), grant.assetId(),
                    atMs(grant.validFromMs()), atMs(grant.validToMs()), true);
        });
    }

    // ---------- 区域编排 ----------

    /**
     * 提交区域编排：逐段校验区域/素材存在、授权未撤销且完整覆盖窗口、同区域时段不重叠
     * （含本次单内与既有占位），全部通过后原子写入占位。携带 requestId 幂等：
     * 同 requestId 重放首次区域清单；换素材或时段返回 409。
     */
    @Transactional
    public RegionScheduleResponse submitSchedule(SubmitRegionScheduleRequest request) {
        String hash = sha256(OP_SUBMIT_REGION_SCHEDULE + "|" + canonicalSegments(request.segments()));
        return idempotent(request.requestId(), OP_SUBMIT_REGION_SCHEDULE, hash,
                RegionScheduleResponse.class, () -> doSubmit(request.requestId(), request.segments()));
    }

    private RegionScheduleResponse doSubmit(String requestId, List<RegionSegmentInput> inputs) {
        // 1. 基本参数与引用完整性：非法区间 400；区域/素材不存在 404（整单回滚）
        List<ParsedSegment> segments = new ArrayList<>();
        for (RegionSegmentInput input : inputs) {
            long startMs = toMs(input.start());
            long endMs = toMs(input.end());
            if (endMs <= startMs) {
                throw ApiException.badRequest(
                        "区域播出时段结束时间必须大于开始时间: " + input.regionId());
            }
            segments.add(new ParsedSegment(input.regionId(), input.assetId(), startMs, endMs));
        }
        for (ParsedSegment segment : segments) {
            Optional<RegionRow> region = regionRepo.findRegion(segment.regionId());
            if (region.isEmpty()) {
                throw ApiException.notFound("区域不存在: " + segment.regionId());
            }
            Optional<AssetRow> asset = requestRepo.findAsset(segment.assetId());
            if (asset.isEmpty()) {
                throw ApiException.notFound("素材不存在: " + segment.assetId());
            }
        }

        // 2. 按区域 ID 排序后依次加区域行锁：同区域并发提交在此串行，排序加锁避免跨区域死锁
        List<String> regionIds = segments.stream().map(ParsedSegment::regionId).distinct().sorted().toList();
        for (String regionId : regionIds) {
            regionRepo.lockRegionForUpdate(regionId);
        }

        List<RegionConflictResponse> conflicts = new ArrayList<>();

        // 3. 单内同区域时段判重（左闭右开，相邻端点可接续）
        List<ParsedSegment> ordered = new ArrayList<>(segments);
        ordered.sort(Comparator.comparing(ParsedSegment::regionId)
                .thenComparingLong(ParsedSegment::startMs)
                .thenComparingLong(ParsedSegment::endMs));
        for (int i = 1; i < ordered.size(); i++) {
            ParsedSegment prev = ordered.get(i - 1);
            ParsedSegment cur = ordered.get(i);
            if (cur.regionId().equals(prev.regionId()) && intervalsOverlap(
                    prev.startMs(), prev.endMs(), cur.startMs(), cur.endMs())) {
                conflicts.add(conflict(cur, "SEGMENT_OVERLAP",
                        "本次提交中同区域时段重叠: " + prev.assetId() + " "
                                + atMs(prev.startMs()) + " ~ " + atMs(prev.endMs())));
            }
        }

        // 4. 逐段加锁授权并校验：撤销/不匹配/未覆盖均计入冲突（撤销事务在此按提交顺序串行）
        List<RegionGrantRow> grants = new ArrayList<>();
        for (ParsedSegment segment : segments) {
            List<RegionGrantRow> candidates = regionRepo.findCoveringGrantsForUpdate(
                    segment.regionId(), segment.assetId(), segment.startMs(), segment.endMs());
            RegionGrantRow active = candidates.stream().filter(g -> !g.revoked()).findFirst().orElse(null);
            if (active != null) {
                grants.add(active);
            } else if (candidates.isEmpty()) {
                conflicts.add(conflict(segment, "NO_COVERING_GRANT",
                        "区域授权不存在或未完整覆盖该时段"));
                grants.add(null);
            } else {
                conflicts.add(conflict(segment, "GRANT_INVALID",
                        "区域授权已撤销: " + candidates.get(0).id()));
                grants.add(candidates.get(0));
            }
        }

        // 5. 与既有占位判重（持区域行锁，查到的为最新已提交占位）
        for (ParsedSegment segment : segments) {
            List<RegionAllocationRow> overlapping =
                    regionRepo.findOverlappingForUpdate(segment.regionId(), segment.startMs(), segment.endMs());
            if (!overlapping.isEmpty()) {
                RegionAllocationRow other = overlapping.get(0);
                conflicts.add(conflict(segment, "REGION_WINDOW_CONFLICT",
                        "与区域已占用时段重叠，冲突占位 requestId=" + other.requestId()
                                + "、素材=" + other.assetId() + "、时段=" + atMs(other.startMs())
                                + " ~ " + atMs(other.endMs())));
            }
        }

        // 6. 任一区域不满足：整单 422，事务回滚，不留下部分区域占位
        if (!conflicts.isEmpty()) {
            throw new RegionScheduleException("REGION_SCHEDULE_INVALID",
                    "区域编排存在 " + conflicts.size() + " 处冲突，整次提交已拒绝", conflicts);
        }

        // 7. 全部通过：原子写入占位，固化素材与授权版本
        long createdAtMs = nowMs();
        List<RegionAllocationResponse> responses = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            ParsedSegment segment = segments.get(i);
            RegionGrantRow grant = grants.get(i);
            long id = regionRepo.insertAllocation(requestId, segment.regionId(), segment.assetId(),
                    grant.id(), grant.validFromMs(), grant.validToMs(),
                    segment.startMs(), segment.endMs(), createdAtMs);
            responses.add(new RegionAllocationResponse(id, requestId, segment.regionId(),
                    segment.assetId(), grant.id(),
                    atMs(grant.validFromMs()), atMs(grant.validToMs()),
                    atMs(segment.startMs()), atMs(segment.endMs()), atMs(createdAtMs)));
        }
        return new RegionScheduleResponse(requestId, responses);
    }

    // ---------- 查询 ----------

    /** 编排员视角：查看各区域占位及固化授权版本。 */
    @Transactional(readOnly = true)
    public List<RegionOccupancyResponse> listAllAllocations() {
        return regionRepo.findAllAllocations().stream()
                .map(RegionPlayoutService::toOccupancy).toList();
    }

    /** 区域播控方视角：只读本区域时段。 */
    @Transactional(readOnly = true)
    public List<RegionOccupancyResponse> listRegionAllocations(String regionId) {
        regionRepo.findRegion(regionId)
                .orElseThrow(() -> ApiException.notFound("区域不存在: " + regionId));
        return regionRepo.findAllocationsByRegion(regionId).stream()
                .map(RegionPlayoutService::toOccupancy).toList();
    }

    // ---------- 内部方法 ----------

    private static RegionConflictResponse conflict(ParsedSegment segment, String reason, String detail) {
        return new RegionConflictResponse(segment.regionId(), segment.assetId(),
                atMs(segment.startMs()), atMs(segment.endMs()), reason, detail);
    }

    private static RegionOccupancyResponse toOccupancy(RegionAllocationRow row) {
        return new RegionOccupancyResponse(row.id(), row.requestId(), row.regionId(), row.assetId(),
                row.grantId(), atMs(row.grantValidFromMs()), atMs(row.grantValidToMs()),
                atMs(row.startMs()), atMs(row.endMs()));
    }

    /**
     * 幂等执行：去重记录与业务结果同事务提交；同 requestId 同参数返回原结果，
     * 同 requestId 不同参数返回 409；业务失败抛异常回滚，不占用 requestId。
     */
    private <T> T idempotent(String requestId, String operation, String paramsHash,
                             Class<T> type, Supplier<T> action) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("requestId 不能为空");
        }
        Optional<RequestRow> existing = requestRepo.findRequestForUpdate(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, paramsHash, type);
        }
        try {
            requestRepo.insertRequest(requestId, operation, paramsHash, nowMs());
        } catch (DuplicateKeyException e) {
            // 并发同 requestId：等待对方事务结束后读取已提交记录
            RequestRow committed = requestRepo.findRequestForUpdate(requestId)
                    .orElseThrow(() -> ApiException.conflict("REQUEST_ID_CONFLICT",
                            "requestId 并发冲突: " + requestId));
            return replay(committed, operation, paramsHash, type);
        }
        T result = action.get();
        try {
            requestRepo.completeRequest(requestId, objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果序列化失败", e);
        }
        return result;
    }

    private <T> T replay(RequestRow row, String operation, String paramsHash, Class<T> type) {
        if (!row.operation().equals(operation) || !row.paramsHash().equals(paramsHash)) {
            throw ApiException.conflict("REQUEST_ID_CONFLICT",
                    "requestId 已使用且参数不一致: " + row.requestId());
        }
        if (row.responseBody() == null) {
            throw ApiException.conflict("REQUEST_ID_CONFLICT",
                    "requestId 请求尚未完成: " + row.requestId());
        }
        try {
            return objectMapper.readValue(row.responseBody(), type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果反序列化失败", e);
        }
    }

    /** 按提交顺序生成参数指纹：区域、素材与 UTC 时段任一变化都会导致重放冲突。 */
    static String canonicalSegments(List<RegionSegmentInput> segments) {
        StringBuilder sb = new StringBuilder();
        for (RegionSegmentInput s : segments) {
            sb.append(s.regionId()).append(',')
                    .append(s.assetId()).append(',')
                    .append(s.start() == null ? "" : toMs(s.start())).append(',')
                    .append(s.end() == null ? "" : toMs(s.end())).append(';');
        }
        return sb.toString();
    }

    private static long toMs(OffsetDateTime time) {
        return time.toInstant().toEpochMilli();
    }

    /**
     * 两个 UTC 区间是否相交，均为左闭右开 [start, end)：
     * {@code aStart < bEnd && bStart < aEnd}；相邻端点（一端 end == 另一端 start）可接续，不算相交。
     * 不同区域的时段各自独立判重，调用方负责先按区域分组。
     */
    static boolean intervalsOverlap(long aStartMs, long aEndMs, long bStartMs, long bEndMs) {
        return aStartMs < bEndMs && bStartMs < aEndMs;
    }

    private static OffsetDateTime atMs(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }

    private static long nowMs() {
        return System.currentTimeMillis();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 解析后的区域时段。 */
    private record ParsedSegment(String regionId, String assetId, long startMs, long endMs) {
    }
}
