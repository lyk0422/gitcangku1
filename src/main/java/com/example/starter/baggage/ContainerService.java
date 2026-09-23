package com.example.starter.baggage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.example.starter.baggage.ContainerDtos.ContainerChainItem;
import com.example.starter.baggage.ContainerDtos.ContainerChainResponse;
import com.example.starter.baggage.ContainerDtos.ContainerLoadRequest;
import com.example.starter.baggage.ContainerDtos.ContainerResponse;
import com.example.starter.baggage.ContainerDtos.ContainerSealRequest;
import com.example.starter.baggage.ContainerDtos.CreateContainerRequest;

/**
 * 行李容器生命周期：创建、装箱、封签与查询。
 * 所有写操作经 {@link IdempotencyService} 去重，业务变更与去重记录原子提交；
 * 容器行级锁（SELECT ... FOR UPDATE）保证装箱/封签/重封并发时以版本决定唯一先后，
 * container_bag 以 bag_tag 为主键保证一件行李任一时刻只属于一个有效容器。
 */
@Service
public class ContainerService {

    static final String CONTAINER_OPEN = "OPEN";
    static final String CONTAINER_SEALED = "SEALED";
    static final String CONTAINER_CLOSED_REPACKED = "CLOSED_REPACKED";

    private static final RowMapper<ContainerRow> CONTAINER_MAPPER = (rs, rowNum) -> new ContainerRow(
            rs.getString("container_id"), rs.getString("leg_id"), rs.getString("handover_point"),
            rs.getString("status"), rs.getInt("version"), rs.getString("seal_no"));

    private final JdbcTemplate jdbcTemplate;
    private final IdempotencyService idempotencyService;
    /** 可控时钟，默认系统 UTC 时钟，测试可替换。 */
    private Supplier<Instant> clock = Instant::now;

    public ContainerService(JdbcTemplate jdbcTemplate, IdempotencyService idempotencyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.idempotencyService = idempotencyService;
    }

    /** 替换时钟（测试使用），容器链进入时刻均取该时钟的 UTC 时刻。 */
    void setClock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    /** 创建容器：containerId 全局唯一，初始状态 OPEN、版本 1。 */
    public ContainerResponse createContainer(CreateContainerRequest request) {
        CreateContainerPayload payload = new CreateContainerPayload(
                request.containerId(), request.legId(), request.handoverPoint());
        return idempotencyService.execute(request.requestId(), "CONTAINER_CREATE", 201,
                payload, ContainerResponse.class, () -> doCreateContainer(request));
    }

    /** 容器装箱：整批原子，任一行李不满足则 422 且无一件入箱。 */
    public ContainerResponse load(String containerId, ContainerLoadRequest request) {
        ContainerLoadPayload payload = new ContainerLoadPayload(
                containerId, request.expectedVersion(), sortedCopy(request.bagTags()));
        return idempotencyService.execute(request.requestId(), "CONTAINER_LOAD", 200,
                payload, ContainerResponse.class, () -> doLoad(containerId, request));
    }

    /** 容器封签：校验版本，sealNo 全局唯一，成功后转 SEALED。 */
    public ContainerResponse seal(String containerId, ContainerSealRequest request) {
        ContainerSealPayload payload = new ContainerSealPayload(
                containerId, request.expectedVersion(), request.sealNo());
        return idempotencyService.execute(request.requestId(), "CONTAINER_SEAL", 200,
                payload, ContainerResponse.class, () -> doSeal(containerId, request));
    }

    /** 容器详情查询：含当前清单（按袋号排序）。 */
    public ContainerResponse getContainer(String containerId) {
        ContainerRow container = findContainer(containerId);
        if (container == null) {
            throw ApiException.notFound("容器不存在: " + containerId);
        }
        return toResponse(container);
    }

    /** 行李容器链查询：逐项保留行李经过的全部容器。 */
    public ContainerChainResponse getBagChain(String bagTag) {
        Integer bagCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag WHERE bag_tag = ?", Integer.class, bagTag);
        if (bagCount == null || bagCount == 0) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        List<ContainerChainItem> chain = jdbcTemplate.query(
                "SELECT seq, container_id, entered_at FROM bag_container_history"
                        + " WHERE bag_tag = ? ORDER BY seq",
                (rs, rowNum) -> new ContainerChainItem(rs.getInt("seq"), rs.getString("container_id"),
                        rs.getObject("entered_at", OffsetDateTime.class).toInstant().toString()),
                bagTag);
        return new ContainerChainResponse(bagTag, chain);
    }

    private ContainerResponse doCreateContainer(CreateContainerRequest request) {
        if (findContainer(request.containerId()) != null) {
            throw ApiException.conflict("容器已存在: " + request.containerId());
        }
        Integer legCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM leg WHERE leg_id = ?", Integer.class, request.legId());
        if (legCount == null || legCount == 0) {
            throw ApiException.unprocessable("容器引用的航段不存在: " + request.legId());
        }
        jdbcTemplate.update(
                "INSERT INTO container (container_id, leg_id, handover_point, status, version)"
                        + " VALUES (?, ?, ?, ?, 1)",
                request.containerId(), request.legId(), request.handoverPoint(), CONTAINER_OPEN);
        return new ContainerResponse(request.containerId(), request.legId(),
                request.handoverPoint(), CONTAINER_OPEN, 1, null, List.of());
    }

    private ContainerResponse doLoad(String containerId, ContainerLoadRequest request) {
        ContainerRow container = lockContainer(containerId);
        checkVersion(container, request.expectedVersion());
        if (!CONTAINER_OPEN.equals(container.status())) {
            throw ApiException.unprocessable("容器状态为 " + container.status() + "，禁止装箱");
        }
        List<String> bagTags = request.bagTags();
        if (new HashSet<>(bagTags).size() != bagTags.size()) {
            throw ApiException.unprocessable("装箱的 bagTag 不得重复");
        }
        List<String> sorted = sortedCopy(bagTags);
        for (String bagTag : sorted) {
            validateLoadable(container, bagTag);
        }
        OffsetDateTime enteredAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        for (String bagTag : sorted) {
            jdbcTemplate.update("INSERT INTO container_bag (bag_tag, container_id) VALUES (?, ?)",
                    bagTag, containerId);
            appendChain(bagTag, containerId, enteredAt);
        }
        int newVersion = container.version() + 1;
        jdbcTemplate.update("UPDATE container SET version = ? WHERE container_id = ?",
                newVersion, containerId);
        return new ContainerResponse(containerId, container.legId(), container.handoverPoint(),
                CONTAINER_OPEN, newVersion, container.sealNo(), sorted);
    }

    private ContainerResponse doSeal(String containerId, ContainerSealRequest request) {
        ContainerRow container = lockContainer(containerId);
        checkVersion(container, request.expectedVersion());
        if (!CONTAINER_OPEN.equals(container.status())) {
            throw ApiException.unprocessable("容器状态为 " + container.status() + "，禁止封签");
        }
        if (sealNoExists(request.sealNo())) {
            throw ApiException.conflict("封签号已存在: " + request.sealNo());
        }
        List<String> manifest = currentBags(containerId);
        int newVersion = container.version() + 1;
        jdbcTemplate.update("UPDATE container SET status = ?, version = ?, seal_no = ?"
                        + " WHERE container_id = ?",
                CONTAINER_SEALED, newVersion, request.sealNo(), containerId);
        return new ContainerResponse(containerId, container.legId(), container.handoverPoint(),
                CONTAINER_SEALED, newVersion, request.sealNo(), manifest);
    }

    /** 装箱校验：行李存在、未短卸、已装载到本容器所属航段且未入任何容器。 */
    private void validateLoadable(ContainerRow container, String bagTag) {
        List<BagState> bags = jdbcTemplate.query(
                "SELECT status, loaded_leg_id FROM bag WHERE bag_tag = ?",
                (rs, rowNum) -> new BagState(rs.getString("status"), rs.getString("loaded_leg_id")),
                bagTag);
        if (bags.isEmpty()) {
            throw ApiException.unprocessable("行李不存在: " + bagTag);
        }
        BagState bag = bags.get(0);
        if ("SHORT_UNLOADED".equals(bag.status())) {
            throw ApiException.unprocessable("行李 " + bagTag + " 处于短卸状态，须先补到才能装箱");
        }
        if (!container.legId().equals(bag.loadedLegId())) {
            throw ApiException.unprocessable(
                    "行李 " + bagTag + " 未装载到航段 " + container.legId() + "，不能装入本容器");
        }
        Integer inContainer = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM container_bag WHERE bag_tag = ?", Integer.class, bagTag);
        if (inContainer != null && inContainer > 0) {
            throw ApiException.unprocessable("行李 " + bagTag + " 已属于其他容器");
        }
    }

    /** 追加行李容器链记录，seq 按该行李已有链长度递增。 */
    void appendChain(String bagTag, String containerId, OffsetDateTime enteredAt) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq) FROM bag_container_history WHERE bag_tag = ?", Integer.class, bagTag);
        int nextSeq = maxSeq == null ? 0 : maxSeq + 1;
        jdbcTemplate.update(
                "INSERT INTO bag_container_history (bag_tag, seq, container_id, entered_at)"
                        + " VALUES (?, ?, ?, ?)",
                bagTag, nextSeq, containerId, enteredAt);
    }

    private void checkVersion(ContainerRow container, int expectedVersion) {
        if (container.version() != expectedVersion) {
            throw ApiException.conflict(
                    "容器版本冲突: 期望 " + expectedVersion + "，当前 " + container.version());
        }
    }

    private boolean sealNoExists(String sealNo) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM container WHERE seal_no = ?", Integer.class, sealNo);
        return count != null && count > 0;
    }

    ContainerRow findContainer(String containerId) {
        List<ContainerRow> rows = jdbcTemplate.query(containerSelect(false), CONTAINER_MAPPER, containerId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    ContainerRow lockContainer(String containerId) {
        List<ContainerRow> rows = jdbcTemplate.query(containerSelect(true), CONTAINER_MAPPER, containerId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("容器不存在: " + containerId);
        }
        return rows.get(0);
    }

    private static String containerSelect(boolean forUpdate) {
        return "SELECT container_id, leg_id, handover_point, status, version, seal_no"
                + " FROM container WHERE container_id = ?" + (forUpdate ? " FOR UPDATE" : "");
    }

    List<String> currentBags(String containerId) {
        return jdbcTemplate.queryForList(
                "SELECT bag_tag FROM container_bag WHERE container_id = ? ORDER BY bag_tag",
                String.class, containerId);
    }

    private ContainerResponse toResponse(ContainerRow container) {
        return new ContainerResponse(container.containerId(), container.legId(),
                container.handoverPoint(), container.status(), container.version(),
                container.sealNo(), currentBags(container.containerId()));
    }

    private static List<String> sortedCopy(List<String> values) {
        return values.stream().sorted().toList();
    }

    /** 容器行记录。 */
    record ContainerRow(String containerId, String legId, String handoverPoint,
                        String status, int version, String sealNo) {
    }

    /** 行李装箱相关状态。 */
    private record BagState(String status, String loadedLegId) {
    }

    /** 创建容器幂等摘要参数。 */
    private record CreateContainerPayload(String containerId, String legId, String handoverPoint) {
    }

    /** 容器装箱幂等摘要参数：bagTags 已排序，顺序差异不视为异参。 */
    private record ContainerLoadPayload(String containerId, int expectedVersion, List<String> bagTags) {
    }

    /** 容器封签幂等摘要参数。 */
    private record ContainerSealPayload(String containerId, int expectedVersion, String sealNo) {
    }
}
