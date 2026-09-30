package com.example.starter.playout;

import com.example.starter.playout.PlayoutRepository.RegionAllocationRow;
import com.example.starter.playout.PlayoutRepository.RegionGrantRow;
import com.example.starter.playout.api.ApiException;
import com.example.starter.playout.api.Dtos.CreateRegionGrantRequest;
import com.example.starter.playout.api.Dtos.CreateRegionRequest;
import com.example.starter.playout.api.Dtos.RegionAllocationItemResponse;
import com.example.starter.playout.api.Dtos.RegionAllocationListResponse;
import com.example.starter.playout.api.Dtos.RegionAllocationStatus;
import com.example.starter.playout.api.Dtos.RegionConflict;
import com.example.starter.playout.api.Dtos.RegionGrantResponse;
import com.example.starter.playout.api.Dtos.RegionResponse;
import com.example.starter.playout.api.Dtos.RegionSegmentInput;
import com.example.starter.playout.api.Dtos.RegionalAllocationRequest;
import com.example.starter.playout.api.Dtos.RegionalAllocationResponse;
import com.example.starter.playout.api.RegionalAllocationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 区域播出编排服务：同一素材可一次分配到多个区域的不同 UTC 播出时段。
 *
 * <p>约定：区域授权与占位均以 UTC 绝对时刻（纪元毫秒）存储，区间左闭右开，相邻端点可接续。
 * 整单提交要么全部成功，要么任一区域授权失效/时段重叠即 422 整单回滚，不留部分区域占位。
 * 占位固化提交时的素材、授权 ID 与授权版本；授权之后撤销或换版只影响之后的编排，历史不追改。
 * 占位状态 SCHEDULED/AIRED 不入库，按查询时钟（endMs 与当前时刻比较，左闭右开）派生。</p>
 */
@Service
public class RegionalPlayoutService {

    /** API 边界输出时区，与既有编排接口保持一致；存储与判重始终使用 UTC 纪元毫秒。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String OP_REVOKE_REGION_GRANT = "REVOKE_REGION_GRANT";
    private static final String OP_REGIONAL_ALLOCATE = "REGIONAL_ALLOCATE";

    private final PlayoutRepository repo;
    private final IdempotencyExecutor idempotency;
    private final Clock clock;

    public RegionalPlayoutService(PlayoutRepository repo, IdempotencyExecutor idempotency,
                                  Clock clock) {
        this.repo = repo;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---------- 区域 ----------

    /** 创建区域；id 为空时生成稳定 ID。 */
    @Transactional
    public RegionResponse createRegion(CreateRegionRequest request) {
        String id = request.id() == null || request.id().isBlank()
                ? UUID.randomUUID().toString() : request.id();
        try {
            repo.insertRegion(id, nowMs());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_ID", "区域 ID 已存在: " + id);
        }
        return new RegionResponse(id);
    }

    // ---------- 区域授权 ----------

    /** 为区域创建某素材的授权窗口；同 region+asset 版本从 1 递增，区间左闭右开。 */
    @Transactional
    public RegionGrantResponse createRegionGrant(String regionId, CreateRegionGrantRequest request) {
        repo.findRegion(regionId)
                .orElseThrow(() -> ApiException.notFound("区域不存在: " + regionId));
        if (repo.findAsset(request.assetId()).isEmpty()) {
            throw ApiException.notFound("素材不存在: " + request.assetId());
        }
        long fromMs = toMs(request.validFrom());
        long toMs = toMs(request.validTo());
        if (toMs <= fromMs) {
            throw ApiException.badRequest("授权有效区间终点必须大于起点");
        }

        // 区域锁内分配版本号，避免并发授权拿到相同版本。
        repo.lockRegionForUpdate(regionId);
        long version = repo.currentRegionGrantVersion(regionId, request.assetId()) + 1;
        long id;
        try {
            id = repo.insertRegionGrant(regionId, request.assetId(), version,
                    fromMs, toMs, nowMs());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("REGION_GRANT_VERSION_CONFLICT",
                    "区域授权版本冲突: " + regionId + " " + request.assetId() + " v" + version);
        }
        return new RegionGrantResponse(id, regionId, request.assetId(), version,
                request.validFrom(), request.validTo(), false);
    }

    /** 撤销区域授权；幂等：同 requestId 同参数返回原结果，改参数 409。撤销只影响之后的编排。 */
    @Transactional
    public RegionGrantResponse revokeRegionGrant(long grantId, String requestId) {
        String hash = IdempotencyExecutor.sha256(OP_REVOKE_REGION_GRANT + "|" + grantId);
        return idempotency.execute(requestId, OP_REVOKE_REGION_GRANT, hash,
                RegionGrantResponse.class, () -> {
                    RegionGrantRow grant = repo.findRegionGrantForUpdate(grantId)
                            .orElseThrow(() -> ApiException.notFound("区域授权不存在: " + grantId));
                    int updated = repo.revokeRegionGrant(grantId, requestId, nowMs());
                    if (updated == 0) {
                        throw ApiException.conflict("GRANT_ALREADY_REVOKED",
                                "区域授权已撤销: " + grantId);
                    }
                    return toGrantResponse(new RegionGrantRow(grant.id(), grant.regionId(),
                            grant.assetId(), grant.version(), grant.validFromMs(),
                            grant.validToMs(), true));
                });
    }

    // ---------- 区域编排整单提交 ----------

    /**
     * 整单提交多区域时段：每段必须落在该区域该素材某条未撤销授权窗口内，且与同区域已提交占位
     * 及同单其他段不重叠（左闭右开，相邻合法）。任一区域不满足即 422 并附全部冲突原因，
     * 事务回滚不留占位、不占 requestId。同 requestId 重放首次清单，换素材/区域/时段返回 409。
     */
    @Transactional
    public RegionalAllocationResponse allocate(RegionalAllocationRequest request) {
        String paramsHash = IdempotencyExecutor.sha256(
                OP_REGIONAL_ALLOCATE + "|" + request.assetId() + "|" + canonicalSegments(request.segments()));
        return idempotency.execute(request.requestId(), OP_REGIONAL_ALLOCATE, paramsHash,
                RegionalAllocationResponse.class, () -> doAllocate(request));
    }

    private RegionalAllocationResponse doAllocate(RegionalAllocationRequest request) {
        List<ParsedSegment> segments = new ArrayList<>();
        Set<String> regionsInRequest = new HashSet<>();
        for (RegionSegmentInput input : request.segments()) {
            long startMs = toMs(input.start());
            long endMs = toMs(input.end());
            if (endMs <= startMs) {
                throw ApiException.badRequest("时段结束时间必须大于开始时间");
            }
            if (!regionsInRequest.add(input.regionId())) {
                throw ApiException.badRequest("同一区域在一次提交中只能出现一个时段: "
                        + input.regionId());
            }
            segments.add(new ParsedSegment(input.regionId(), startMs, endMs));
        }

        // 素材存在性在加锁前判定。
        if (repo.findAsset(request.assetId()).isEmpty()) {
            throw ApiException.notFound("素材不存在: " + request.assetId());
        }

        // 按区域 ID 排序加区域锁：同区域并发提交在此串行化（并发占用同一区域窗口最多一方成功），
        // 不相交区域集合互不阻塞；固定加锁顺序避免死锁。
        List<String> sortedRegions = new ArrayList<>(regionsInRequest);
        sortedRegions.sort(Comparator.naturalOrder());
        for (String regionId : sortedRegions) {
            if (repo.findRegion(regionId).isEmpty()) {
                throw ApiException.notFound("区域不存在: " + regionId);
            }
            repo.lockRegionForUpdate(regionId);
        }

        long nowMs = nowMs();
        List<RegionConflict> conflicts = new ArrayList<>();
        List<RegionAllocationRow> toInsert = new ArrayList<>();
        for (ParsedSegment segment : segments) {
            // 1) 授权窗口：加行锁选取版本最高的未撤销完整覆盖授权；与撤销事务按提交顺序串行。
            List<RegionGrantRow> grants = repo.findCoveringRegionGrantsForUpdate(
                    segment.regionId, request.assetId(), segment.startMs, segment.endMs);
            RegionGrantRow selected = grants.stream().filter(g -> !g.revoked()).findFirst()
                    .orElse(null);
            if (selected == null) {
                conflicts.add(conflict(segment, request.assetId(),
                        "GRANT_NOT_COVERING", null));
                continue;
            }
            // 2) 与已提交占位判重（左闭右开，相邻端点可接续）。
            List<RegionAllocationRow> overlapping = repo.findRegionOverlapsForUpdate(
                    segment.regionId, segment.startMs, segment.endMs);
            if (!overlapping.isEmpty()) {
                RegionAllocationRow hit = overlapping.get(0);
                conflicts.add(conflict(segment, request.assetId(),
                        "REGION_WINDOW_OVERLAP", hit));
                continue;
            }
            toInsert.add(new RegionAllocationRow(0, request.requestId(), segment.regionId,
                    request.assetId(), selected.id(), selected.version(),
                    segment.startMs, segment.endMs()));
        }

        if (!conflicts.isEmpty()) {
            // 整单拒绝：异常触发事务回滚，上面的加锁/判定均不落任何占位。
            throw new RegionalAllocationException(
                    "区域编排整单被拒绝，存在 " + conflicts.size() + " 个区域冲突", conflicts);
        }

        List<RegionAllocationItemResponse> items = new ArrayList<>();
        for (RegionAllocationRow row : toInsert) {
            long allocationId = repo.insertRegionAllocation(row.requestId(), row.regionId(),
                    row.assetId(), row.grantId(), row.grantVersion(),
                    row.startMs(), row.endMs(), nowMs);
            items.add(toItemResponse(new RegionAllocationRow(allocationId, row.requestId(),
                    row.regionId(), row.assetId(), row.grantId(), row.grantVersion(),
                    row.startMs(), row.endMs()), nowMs));
        }
        return new RegionalAllocationResponse(request.requestId(), request.assetId(), items);
    }

    // ---------- 查询 ----------

    /** 编排员视图：全部区域占位。 */
    @Transactional(readOnly = true)
    public RegionAllocationListResponse listAll() {
        long nowMs = nowMs();
        List<RegionAllocationItemResponse> items = repo.findAllRegionAllocations().stream()
                .map(row -> toItemResponse(row, nowMs)).toList();
        return new RegionAllocationListResponse(items);
    }

    /** 区域播控方视图：只读本区域时段。 */
    @Transactional(readOnly = true)
    public RegionAllocationListResponse listByRegion(String regionId) {
        if (repo.findRegion(regionId).isEmpty()) {
            throw ApiException.notFound("区域不存在: " + regionId);
        }
        long nowMs = nowMs();
        List<RegionAllocationItemResponse> items = repo.findRegionAllocations(regionId).stream()
                .map(row -> toItemResponse(row, nowMs)).toList();
        return new RegionAllocationListResponse(items);
    }

    // ---------- 内部方法 ----------

    private RegionConflict conflict(ParsedSegment segment, String assetId,
                                    String reason, RegionAllocationRow conflicting) {
        return new RegionConflict(segment.regionId, assetId, atMs(segment.startMs),
                atMs(segment.endMs), reason,
                conflicting == null ? null : conflicting.requestId(),
                conflicting == null ? null : conflicting.assetId(),
                conflicting == null ? null : atMs(conflicting.startMs()),
                conflicting == null ? null : atMs(conflicting.endMs()));
    }

    private static RegionGrantResponse toGrantResponse(RegionGrantRow row) {
        return new RegionGrantResponse(row.id(), row.regionId(), row.assetId(), row.version(),
                atMs(row.validFromMs()), atMs(row.validToMs()), row.revoked());
    }

    private RegionAllocationItemResponse toItemResponse(RegionAllocationRow row, long nowMs) {
        RegionAllocationStatus status = nowMs >= row.endMs()
                ? RegionAllocationStatus.AIRED : RegionAllocationStatus.SCHEDULED;
        return new RegionAllocationItemResponse(row.id(), row.requestId(), row.regionId(),
                row.assetId(), atMs(row.startMs()), atMs(row.endMs()),
                row.grantId(), row.grantVersion(), status);
    }

    private static String canonicalSegments(List<RegionSegmentInput> segments) {
        StringBuilder sb = new StringBuilder();
        for (RegionSegmentInput s : segments) {
            sb.append(s.regionId()).append(',')
                    .append(toMs(s.start())).append(',')
                    .append(toMs(s.end())).append(';');
        }
        return sb.toString();
    }

    private long nowMs() {
        return clock.millis();
    }

    private static long toMs(OffsetDateTime time) {
        return time.toInstant().toEpochMilli();
    }

    private static OffsetDateTime atMs(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZONE);
    }

    /** 解析后的区域时段。 */
    private record ParsedSegment(String regionId, long startMs, long endMs) {
    }
}
