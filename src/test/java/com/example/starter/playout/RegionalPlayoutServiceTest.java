package com.example.starter.playout;

import com.example.starter.playout.PlayoutRepository.RegionAllocationRow;
import com.example.starter.playout.PlayoutRepository.RegionGrantRow;
import com.example.starter.playout.api.ApiException;
import com.example.starter.playout.api.Dtos.CreateRegionGrantRequest;
import com.example.starter.playout.api.Dtos.RegionAllocationStatus;
import com.example.starter.playout.api.Dtos.RegionSegmentInput;
import com.example.starter.playout.api.Dtos.RegionalAllocationRequest;
import com.example.starter.playout.api.Dtos.RegionalAllocationResponse;
import com.example.starter.playout.api.RegionalAllocationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 区域编排服务单元测试：授权/重叠冲突聚合、整单不留占位、授权版本选取、幂等换参 409、
 * 状态按时钟派生与撤销分支。仓储层全部 mock，数据库约束由 H2 API 测试另行真实验证。
 */
@ExtendWith(MockitoExtension.class)
class RegionalPlayoutServiceTest {

    private static final OffsetDateTime T_10 = OffsetDateTime.of(2026, 9, 22, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime T_1030 = T_10.plusMinutes(30);
    private static final OffsetDateTime T_1045 = T_10.plusMinutes(45);
    private static final OffsetDateTime T_11 = T_10.plusHours(1);
    private static final OffsetDateTime T_12 = T_10.plusHours(2);

    @Mock
    private PlayoutRepository repo;

    private MutableClock clock;
    private RegionalPlayoutService service;
    private IdempotencyExecutor idempotency;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-22T00:00:00Z"));
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        idempotency = new IdempotencyExecutor(repo, objectMapper, clock);
        service = new RegionalPlayoutService(repo, idempotency, clock);
    }

    private void assumeRegionExists(String... regionIds) {
        for (String regionId : regionIds) {
            when(repo.findRegion(regionId))
                    .thenReturn(Optional.of(new PlayoutRepository.RegionRow(regionId)));
        }
    }

    private RegionGrantRow grant(long id, String regionId, long version) {
        return new RegionGrantRow(id, regionId, "S", version,
                T_10.toInstant().toEpochMilli(), T_12.toInstant().toEpochMilli(), false);
    }

    private RegionSegmentInput segment(String regionId, OffsetDateTime start, OffsetDateTime end) {
        return new RegionSegmentInput(regionId, start, end);
    }

    @Test
    void allocateTwoRegionsSucceedsAndSelectsHighestGrantVersion() {
        assumeRegionExists("A", "B");
        when(repo.findAsset("S")).thenReturn(Optional.of(
                new PlayoutRepository.AssetRow("S", 3600000)));
        // A 有 v1、v3、v2 三条覆盖授权，撤销 v3 后应选 v2；B 只有 v1
        when(repo.findCoveringRegionGrantsForUpdate(eq("A"), eq("S"), anyLong(), anyLong()))
                .thenReturn(List.of(grant(3, "A", 3), grant(2, "A", 2), grant(1, "A", 1)));
        when(repo.findCoveringRegionGrantsForUpdate(eq("B"), eq("S"), anyLong(), anyLong()))
                .thenReturn(List.of(grant(11, "B", 1)));
        when(repo.findRegionOverlapsForUpdate(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of());
        when(repo.insertRegionAllocation(anyString(), anyString(), anyString(), anyLong(), anyLong(),
                anyLong(), anyLong(), anyLong())).thenReturn(101L, 102L);

        RegionalAllocationRequest request = new RegionalAllocationRequest("req-1", "S",
                List.of(segment("A", T_10, T_11), segment("B", T_11, T_12)));
        RegionalAllocationResponse response = service.allocate(request);

        assertThat(response.requestId()).isEqualTo("req-1");
        assertThat(response.assetId()).isEqualTo("S");
        assertThat(response.segments()).hasSize(2);
        // 按请求顺序返回；A 选最高版本 v3
        assertThat(response.segments().get(0).regionId()).isEqualTo("A");
        assertThat(response.segments().get(0).grantId()).isEqualTo(3L);
        assertThat(response.segments().get(0).grantVersion()).isEqualTo(3L);
        assertThat(response.segments().get(0).status()).isEqualTo(RegionAllocationStatus.SCHEDULED);
        assertThat(response.segments().get(1).regionId()).isEqualTo("B");
        assertThat(response.segments().get(1).grantVersion()).isEqualTo(1L);
        verify(repo).insertRegionAllocation(eq("req-1"), eq("A"), eq("S"), eq(3L), eq(3L),
                anyLong(), anyLong(), anyLong());
        verify(repo).insertRegionAllocation(eq("req-1"), eq("B"), eq("S"), eq(11L), eq(1L),
                anyLong(), anyLong(), anyLong());
    }

    @Test
    void grantFailureAndOverlapAggregatedLeavesNoPlaceholders() {
        assumeRegionExists("A", "B", "C");
        when(repo.findAsset("S")).thenReturn(Optional.of(
                new PlayoutRepository.AssetRow("S", 3600000)));
        // A 无授权；B 授权有效但窗口被占；C 正常
        when(repo.findCoveringRegionGrantsForUpdate(eq("A"), eq("S"), anyLong(), anyLong()))
                .thenReturn(List.of());
        when(repo.findCoveringRegionGrantsForUpdate(eq("B"), eq("S"), anyLong(), anyLong()))
                .thenReturn(List.of(grant(21, "B", 1)));
        when(repo.findCoveringRegionGrantsForUpdate(eq("C"), eq("S"), anyLong(), anyLong()))
                .thenReturn(List.of(grant(31, "C", 1)));
        RegionAllocationRow existing = new RegionAllocationRow(900, "old-req", "B", "S",
                21, 1, T_10.toInstant().toEpochMilli(), T_11.toInstant().toEpochMilli());
        when(repo.findRegionOverlapsForUpdate(eq("B"), anyLong(), anyLong()))
                .thenReturn(List.of(existing));
        when(repo.findRegionOverlapsForUpdate(eq("C"), anyLong(), anyLong()))
                .thenReturn(List.of());

        RegionalAllocationRequest request = new RegionalAllocationRequest("req-2", "S", List.of(
                segment("A", T_1030, T_1045),
                segment("B", T_1030, T_1045),
                segment("C", T_1030, T_1045)));
        assertThatThrownBy(() -> service.allocate(request))
                .isInstanceOfSatisfying(RegionalAllocationException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.conflicts()).hasSize(2);
                    assertThat(ex.conflicts()).extracting("regionId", "reason")
                            .containsExactlyInAnyOrder(
                                    org.assertj.core.groups.Tuple.tuple("A", "GRANT_NOT_COVERING"),
                                    org.assertj.core.groups.Tuple.tuple("B", "REGION_WINDOW_OVERLAP"));
                    assertThat(ex.conflicts().get(1)).isNotNull();
                });

        // C 段虽然本身合法，但整单失败时不得留下任何占位
        verify(repo, never()).insertRegionAllocation(anyString(), anyString(), anyString(),
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void duplicateRegionAndInvalidIntervalRejectedBeforeLocks() {
        RegionalAllocationRequest duplicateRegion = new RegionalAllocationRequest("req-3", "S",
                List.of(segment("A", T_10, T_11), segment("A", T_11, T_12)));
        assertThatThrownBy(() -> service.allocate(duplicateRegion))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        RegionalAllocationRequest badInterval = new RegionalAllocationRequest("req-4", "S",
                List.of(segment("A", T_11, T_10)));
        assertThatThrownBy(() -> service.allocate(badInterval))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(repo, never()).lockRegionForUpdate(anyString());
        verify(repo, never()).findAsset(anyString());
        verify(repo, never()).insertRegionAllocation(anyString(), anyString(), anyString(),
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void unknownAssetIsNotFound() {
        when(repo.findAsset("S")).thenReturn(Optional.empty());
        RegionalAllocationRequest request = new RegionalAllocationRequest("req-5", "S",
                List.of(segment("A", T_10, T_11)));
        assertThatThrownBy(() -> service.allocate(request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void idempotentReplayReturnsFirstResultAndChangedAssetConflicts() {
        assumeRegionExists("A");
        when(repo.findAsset("S")).thenReturn(Optional.of(
                new PlayoutRepository.AssetRow("S", 3600000)));
        when(repo.findCoveringRegionGrantsForUpdate(eq("A"), eq("S"), anyLong(), anyLong()))
                .thenReturn(List.of(grant(7, "A", 1)));
        when(repo.findRegionOverlapsForUpdate(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of());
        when(repo.insertRegionAllocation(anyString(), anyString(), anyString(), anyLong(), anyLong(),
                anyLong(), anyLong(), anyLong())).thenReturn(700L);

        // 用内存表模拟 playout_request：首次无记录，插入占位行，完成时写回响应体与真实参数指纹。
        java.util.Map<String, String> storedHash = new java.util.HashMap<>();
        java.util.Map<String, String> storedBody = new java.util.HashMap<>();
        when(repo.findRequestForUpdate("req-idem")).thenAnswer(inv -> {
            String body = storedBody.get("req-idem");
            return body == null ? Optional.empty()
                    : Optional.of(new PlayoutRepository.RequestRow(
                            "req-idem", "REGIONAL_ALLOCATE", storedHash.get("req-idem"), body));
        });
        org.mockito.Mockito.doAnswer(inv -> {
            storedHash.put("req-idem", inv.getArgument(2));
            return 1;
        }).when(repo).insertRequest(eq("req-idem"), eq("REGIONAL_ALLOCATE"),
                org.mockito.ArgumentMatchers.anyString(), anyLong());
        org.mockito.Mockito.doAnswer(inv -> {
            storedBody.put("req-idem", inv.getArgument(1));
            return 1;
        }).when(repo).completeRequest(eq("req-idem"), org.mockito.ArgumentMatchers.anyString());

        RegionalAllocationRequest first = new RegionalAllocationRequest("req-idem", "S",
                List.of(segment("A", T_10, T_11)));
        RegionalAllocationResponse firstResponse = service.allocate(first);
        assertThat(firstResponse.segments().get(0).allocationId()).isEqualTo(700L);

        // 同 requestId 同参数重放：反序列化首次结果，不再插入占位
        RegionalAllocationResponse replayed = service.allocate(first);
        assertThat(replayed.segments().get(0).allocationId()).isEqualTo(700L);
        verify(repo, org.mockito.Mockito.times(1)).insertRegionAllocation(anyString(), anyString(),
                anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());

        // 换素材：paramsHash 不一致 → 409
        assertThatThrownBy(() -> service.allocate(new RegionalAllocationRequest("req-idem", "T",
                List.of(segment("A", T_10, T_11)))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("REQUEST_ID_CONFLICT");
                });
        // 409 未执行业务动作，仍只有一次占位插入
        verify(repo, org.mockito.Mockito.times(1)).insertRegionAllocation(anyString(), anyString(),
                anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void statusDerivedFromClockScheduledThenAiredAndHistoryNotRewritten() {
        RegionAllocationRow row = new RegionAllocationRow(1, "req-h", "A", "S", 7, 1,
                T_10.toInstant().toEpochMilli(), T_11.toInstant().toEpochMilli());
        when(repo.findRegion("A")).thenReturn(Optional.of(new PlayoutRepository.RegionRow("A")));
        when(repo.findRegionAllocations("A")).thenReturn(List.of(row));

        // 时钟在 10:30：SCHEDULED
        clock.setInstant(T_1030.toInstant());
        assertThat(service.listByRegion("A").segments().get(0).status())
                .isEqualTo(RegionAllocationStatus.SCHEDULED);

        // 时钟推进到结束时刻 11:00（左闭右开，end 不含）：AIRED；固化字段不随时钟或授权变化
        clock.setInstant(T_11.toInstant());
        var item = service.listByRegion("A").segments().get(0);
        assertThat(item.status()).isEqualTo(RegionAllocationStatus.AIRED);
        assertThat(item.assetId()).isEqualTo("S");
        assertThat(item.grantId()).isEqualTo(7L);
        assertThat(item.grantVersion()).isEqualTo(1L);
    }

    @Test
    void revokeRegionGrantBranches() {
        when(repo.findRegionGrantForUpdate(42L)).thenReturn(Optional.of(grant(42, "A", 1)));
        when(repo.revokeRegionGrant(eq(42L), anyString(), anyLong())).thenReturn(1);
        when(repo.findRequestForUpdate("rv-1")).thenReturn(Optional.empty());

        var response = service.revokeRegionGrant(42L, "rv-1");
        assertThat(response.revoked()).isTrue();
        assertThat(response.version()).isEqualTo(1L);

        // 已撤销：受影响行数 0 → 409
        when(repo.findRegionGrantForUpdate(43L)).thenReturn(Optional.of(grant(43, "A", 1)));
        when(repo.revokeRegionGrant(eq(43L), anyString(), anyLong())).thenReturn(0);
        assertThatThrownBy(() -> service.revokeRegionGrant(43L, "rv-2"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("GRANT_ALREADY_REVOKED");
                });

        // 不存在：404
        when(repo.findRegionGrantForUpdate(44L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.revokeRegionGrant(44L, "rv-3"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void createRegionGrantAssignsIncrementingVersionUnderRegionLock() {
        assumeRegionExists("A");
        when(repo.findAsset("S")).thenReturn(Optional.of(
                new PlayoutRepository.AssetRow("S", 3600000)));
        when(repo.currentRegionGrantVersion("A", "S")).thenReturn(0L, 2L);
        when(repo.insertRegionGrant(eq("A"), eq("S"), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(1L, 2L);

        CreateRegionGrantRequest request = new CreateRegionGrantRequest("S", T_10, T_11);
        var first = service.createRegionGrant("A", request);
        var third = service.createRegionGrant("A", request);
        assertThat(first.version()).isEqualTo(1L);
        assertThat(third.version()).isEqualTo(3L);
        verify(repo, org.mockito.Mockito.times(2)).lockRegionForUpdate("A");
    }
}
