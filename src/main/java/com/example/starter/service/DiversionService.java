package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.AirportCreateRequest;
import com.example.starter.api.dto.AirportResult;
import com.example.starter.api.dto.DepartureRequest;
import com.example.starter.api.dto.DepartureResult;
import com.example.starter.api.dto.DisplacedReservationDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyEntry;
import com.example.starter.api.dto.OccupancyView;
import com.example.starter.api.dto.ReplacementLink;
import com.example.starter.api.dto.ReservationRequest;
import com.example.starter.api.dto.ReservationResult;
import com.example.starter.domain.DiversionPlanner;
import com.example.starter.domain.ReservationStatus;
import com.example.starter.repo.DiversionAirportPo;
import com.example.starter.repo.DiversionRepository;
import com.example.starter.repo.ReservationPo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 备降机场预留业务服务。
 *
 * <p>航线按优先级（数值越大越高）申请机场与 UTC 左闭右开窗口，预留占用机场窗口容量。
 * 高优先级申请可整体挤出尚未起飞的低优先级预留，并在同一事务内一次写入替换关系
 * （replaced_by 不可变）与新占用记录；相同优先级不得互相挤出；窗口重叠且无法合法
 * 挤出时返回 409；容量、机场或时间参数不合法返回 422。任何拒绝都回滚事务，
 * 原预留与容量保持不变；已起飞预留不能被挤出。</p>
 *
 * <p>同一机场的申请/起飞事务先对机场行做真实更新取得行级排他锁后再裁决，
 * 因此并发申请严格按提交顺序串行，按提交时容量裁决，不超卖，也不会只挤出
 * 部分低优先级航线。</p>
 */
@Service
public class DiversionService {

    static final String KIND_AIRPORT_CREATE = "DIVERSION_AIRPORT_CREATE";
    static final String KIND_RESERVATION = "DIVERSION_RESERVATION";
    static final String KIND_DEPARTURE = "DIVERSION_DEPARTURE";

    private final DiversionRepository diversionRepo;
    private final IdempotentExecutor idempotency;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public DiversionService(DiversionRepository diversionRepo, IdempotentExecutor idempotency,
                            Clock clock, PlatformTransactionManager transactionManager) {
        this.diversionRepo = diversionRepo;
        this.idempotency = idempotency;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ============================ 机场 ============================

    /** 创建备降机场；容量非正返回 422，机场重复返回 409。 */
    public MutationResponse createAirport(AirportCreateRequest request) {
        return idempotency.execute(request.requestId(), KIND_AIRPORT_CREATE,
                idempotency.canonicalHash(request), () -> {
                    if (request.capacity() <= 0) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CAPACITY",
                                "机场容量必须为正整数：capacity=" + request.capacity());
                    }
                    if (diversionRepo.findAirport(request.airportId()) != null) {
                        throw new ApiException(HttpStatus.CONFLICT, "AIRPORT_ALREADY_EXISTS",
                                "备降机场已存在: " + request.airportId());
                    }
                    diversionRepo.insertAirport(
                            new DiversionAirportPo(request.airportId(), request.capacity(), 0L));
                    return new MutationResponse(request.requestId(), false,
                            new AirportResult(request.airportId(), request.capacity()));
                });
    }

    // ============================ 预留申请 ============================

    /**
     * 申请备降预留。窗口非法/机场不存在返回 422；窗口重叠且无法合法挤出返回 409。
     * 成功时在同一事务写入新占用与全部替换关系，响应携带被整体挤出清单。
     */
    public MutationResponse reserve(ReservationRequest request) {
        return idempotency.execute(request.requestId(), KIND_RESERVATION,
                idempotency.canonicalHash(request), () -> {
                    if (request.windowStart() >= request.windowEnd()) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_WINDOW",
                                "UTC 窗口必须非退化且左闭右开：windowStart < windowEnd");
                    }
                    // 先锁机场行：同一机场的申请/起飞在此串行，按提交时容量裁决
                    DiversionAirportPo airport = diversionRepo.lockAirport(request.airportId());
                    if (airport == null) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AIRPORT_NOT_FOUND",
                                "备降机场不存在: " + request.airportId());
                    }
                    // 持锁读取当前重叠占用：未起飞与已起飞预留都占容量；
                    // 只有未起飞（RESERVED）可被合法挤出，已起飞是不可移动阻挡者
                    List<ReservationPo> overlapping = diversionRepo.findOccupyingOverlapping(
                            request.airportId(), request.windowStart(), request.windowEnd());
                    List<DiversionPlanner.Occupant> occupants = new ArrayList<>(overlapping.size());
                    for (ReservationPo po : overlapping) {
                        boolean displaceable =
                                ReservationStatus.RESERVED.name().equals(po.status());
                        occupants.add(new DiversionPlanner.Occupant(po.reservationId(), po.routeId(),
                                po.priority(), po.windowStart(), po.windowEnd(), displaceable));
                    }
                    DiversionPlanner.Plan plan = DiversionPlanner.plan(airport.capacity(), occupants,
                            request.priority(), request.windowStart(), request.windowEnd());
                    if (plan.conflict()) {
                        // 任何拒绝都不写入：抛出后整事务回滚，原预留与容量不变
                        throw new ApiException(HttpStatus.CONFLICT, "WINDOW_CONFLICT",
                                "窗口与不能挤出的同级或更高级预留重叠，阻挡预留: "
                                        + plan.blockingReservationId());
                    }

                    long now = nowMillis();
                    String newReservationId = "dr_" + UUID.randomUUID();
                    diversionRepo.insertReservation(new ReservationPo(
                            newReservationId, request.airportId(), request.routeId(),
                            request.priority(), request.windowStart(), request.windowEnd(),
                            ReservationStatus.RESERVED.name(), null, request.requestId(), now, now));

                    // 一次写入全部替换关系；任一被挤预留已不再是 RESERVED 都判定失败并整体回滚，
                    // 杜绝只挤出部分低优先级航线（持机场行锁时理论上不会发生，此处为数据兜底）
                    List<DisplacedReservationDto> displaced = new ArrayList<>(plan.displaced().size());
                    for (DiversionPlanner.Occupant victim : plan.displaced()) {
                        int updated = diversionRepo.markDisplacedIfReserved(
                                victim.reservationId(), newReservationId, now);
                        if (updated == 0) {
                            throw new ApiException(HttpStatus.CONFLICT, "WINDOW_CONFLICT",
                                    "被挤预留已起飞或状态变化，整体申请拒绝: "
                                            + victim.reservationId());
                        }
                        displaced.add(new DisplacedReservationDto(victim.reservationId(),
                                victim.routeId(), victim.priority(), newReservationId));
                    }
                    displaced.sort(java.util.Comparator.comparing(DisplacedReservationDto::reservationId));
                    return new MutationResponse(request.requestId(), false, new ReservationResult(
                            newReservationId, request.airportId(), request.routeId(),
                            request.priority(), request.windowStart(), request.windowEnd(),
                            ReservationStatus.RESERVED.name(), List.copyOf(displaced)));
                });
    }

    // ============================ 起飞 ============================

    /**
     * 标记预留已起飞：状态变为 DEPARTED，此后该预留仍占窗口容量但不能再被挤出。
     * 预留不存在返回 404；已起飞/已被挤出再次操作返回 409。
     */
    public MutationResponse depart(DepartureRequest request) {
        return idempotency.execute(request.requestId(), KIND_DEPARTURE,
                idempotency.canonicalHash(request), () -> {
                    ReservationPo reservation = diversionRepo.findReservation(request.reservationId());
                    if (reservation == null) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND",
                                "预留不存在: " + request.reservationId());
                    }
                    // 锁机场行，与该机场的申请事务互斥，避免“裁决中被挤出”与“起飞”并发
                    DiversionAirportPo airport = diversionRepo.lockAirport(reservation.airportId());
                    if (airport == null) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "AIRPORT_NOT_FOUND",
                                "预留所属机场不存在: " + reservation.airportId());
                    }
                    int updated = diversionRepo.markDepartedIfReserved(
                            request.reservationId(), nowMillis());
                    if (updated == 0) {
                        ReservationPo current = diversionRepo.findReservation(request.reservationId());
                        String status = current == null ? "UNKNOWN" : current.status();
                        throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_NOT_ACTIVE",
                                "预留不是可起飞状态（可能已起飞或已被挤出）: " + status);
                    }
                    return new MutationResponse(request.requestId(), false,
                            new DepartureResult(request.reservationId(),
                                    ReservationStatus.DEPARTED.name()));
                });
    }

    // ============================ 查询 ============================

    /**
     * 按机场与 UTC 左闭右开时段查询当前占用、优先级与不可变替换链。
     * 机场不存在或时段非法返回 422；返回全部与查询时段重叠的预留（含已起飞、
     * 已被挤出记录），替换关系一经写入永不改变，后续起飞不改写旧替换结果。
     */
    public OccupancyView getOccupancy(String airportId, long from, long to) {
        return txTemplate.execute(status -> {
            if (from >= to) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_WINDOW",
                        "查询时段必须非退化且左闭右开：from < to");
            }
            DiversionAirportPo airport = diversionRepo.findAirport(airportId);
            if (airport == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AIRPORT_NOT_FOUND",
                        "备降机场不存在: " + airportId);
            }
            List<ReservationPo> rows = diversionRepo.findOverlappingAnyStatus(airportId, from, to);
            List<OccupancyEntry> entries = new ArrayList<>(rows.size());
            for (ReservationPo po : rows) {
                entries.add(new OccupancyEntry(po.reservationId(), po.routeId(), po.priority(),
                        po.windowStart(), po.windowEnd(), po.status(), po.replacedBy(),
                        buildReplacementChain(po)));
            }
            return new OccupancyView(airportId, airport.capacity(), from, to,
                    List.copyOf(entries));
        });
    }

    /**
     * 构造不可变替换链：从给定预留开始，每项为“source 被 target 挤出”，
     * 沿 replaced_by 直到末端。target 的状态字段反映其当前状态（可能已再次被挤出或
     * 已起飞），但 source→target 的替换关系本身永不变更。
     */
    private List<ReplacementLink> buildReplacementChain(ReservationPo start) {
        List<ReplacementLink> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(start.reservationId());
        ReservationPo current = start;
        while (current.replacedBy() != null) {
            ReservationPo target = diversionRepo.findReservation(current.replacedBy());
            if (target == null || !visited.add(target.reservationId())) {
                // 防御性分支：数据正常时替换链始终指向存在的更新预留且不可能成环
                break;
            }
            chain.add(new ReplacementLink(current.reservationId(), target.reservationId(),
                    target.routeId(), target.priority(), target.status()));
            current = target;
        }
        return List.copyOf(chain);
    }

    private long nowMillis() {
        return clock.instant().toEpochMilli();
    }
}
