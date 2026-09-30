package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.AirportCreateRequest;
import com.example.starter.api.dto.AirportResult;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyDto;
import com.example.starter.api.dto.OccupancyReportDto;
import com.example.starter.api.dto.ReplacementDto;
import com.example.starter.api.dto.ReservationCreateRequest;
import com.example.starter.api.dto.ReservationDepartRequest;
import com.example.starter.api.dto.ReservationResultDto;
import com.example.starter.api.dto.ReservationStateDto;
import com.example.starter.domain.DisplacementPlanner;
import com.example.starter.repo.AirportPo;
import com.example.starter.repo.ReplacementPo;
import com.example.starter.repo.ReservationPo;
import com.example.starter.repo.ReservationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 备降机场预留业务服务。
 *
 * <p>预留事务先对机场行做真实更新（touch 加一）取得行级排他锁，再读取重叠占用并裁决，
 * 因而同机场并发预留按提交顺序串行化：竞争申请按提交时容量裁决，不超卖，
 * 挤出要么整体完成（新预留、替换事件、旧预留置 DISPLACED 同事务一次写入），
 * 要么 409 回滚、原预留与容量保持不变。</p>
 *
 * <p>优先级数值越大越高：高优先级申请可整体挤出尚未起飞的低优先级预留；
 * 同级与已起飞预留不可挤出。窗口为 UTC 左闭右开区间。</p>
 */
@Service
public class DiversionReservationService {

    static final String KIND_AIRPORT_CREATE = "DIVERSION_AIRPORT_CREATE";
    static final String KIND_RESERVE = "DIVERSION_RESERVE";
    static final String KIND_DEPART = "DIVERSION_DEPART";

    private final ReservationRepository reservationRepo;
    private final IdempotentExecutor idempotency;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public DiversionReservationService(ReservationRepository reservationRepo,
                                       IdempotentExecutor idempotency,
                                       Clock clock,
                                       PlatformTransactionManager transactionManager) {
        this.reservationRepo = reservationRepo;
        this.idempotency = idempotency;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ============================ 机场 ============================

    /** 创建备降机场；容量必须 &gt;=1（否则 422），airportId 重复返回 409。 */
    public MutationResponse createAirport(AirportCreateRequest request) {
        return idempotency.execute(request.requestId(), KIND_AIRPORT_CREATE,
                idempotency.canonicalHash(request), () -> {
                    if (request.capacity() == null || request.capacity() < 1) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "INVALID_AIRPORT_CAPACITY",
                                "机场窗口容量必须为正整数（>=1）: " + request.capacity());
                    }
                    if (reservationRepo.findAirport(request.airportId()) != null) {
                        throw new ApiException(HttpStatus.CONFLICT, "AIRPORT_ALREADY_EXISTS",
                                "备降机场已存在: " + request.airportId());
                    }
                    reservationRepo.insertAirport(new AirportPo(request.airportId(),
                            request.capacity(), 0L, nowMillis()));
                    return new MutationResponse(request.requestId(), false,
                            new AirportResult(request.airportId(), request.capacity()));
                });
    }

    // ============================ 预留 ============================

    /**
     * 申请备降预留。
     *
     * <p>持机场行锁读取重叠占用并裁决：容量足够直接占用；不足时整体挤出可合法挤出的
     * 低优先级未起飞预留，并在同一事务写入新预留与全部不可变替换事件；
     * 窗口重叠且存在无法合法挤出的占用时返回 409 并回滚。
     * 机场不存在或窗口参数不合法返回 422。</p>
     */
    public MutationResponse reserve(ReservationCreateRequest request) {
        return idempotency.execute(request.requestId(), KIND_RESERVE,
                idempotency.canonicalHash(request),
                () -> doReserve(request));
    }

    private MutationResponse doReserve(ReservationCreateRequest request) {
        if (request.windowStart() == null || request.windowEnd() == null
                || request.windowStart() >= request.windowEnd()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_WINDOW",
                    "窗口必须为 UTC 左闭右开非空区间：windowStart < windowEnd");
        }
        // 先锁机场行：与同机场的其他预留/起飞事务互斥，保证裁决基于提交时容量
        AirportPo airport = reservationRepo.lockAirport(request.airportId());
        if (airport == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AIRPORT_NOT_FOUND",
                    "备降机场不存在: " + request.airportId());
        }

        List<ReservationPo> overlapping = reservationRepo.findReservationsOverlapping(
                request.airportId(), request.windowStart(), request.windowEnd());
        DisplacementPlanner.Decision decision = DisplacementPlanner.plan(
                airport.capacity(), request.windowStart(), request.windowEnd(),
                request.priority(), overlapping);
        if (!decision.feasible()) {
            // 任何拒绝都在事务内抛出并整体回滚：原预留与容量保持不变
            if (DisplacementPlanner.BLOCKED_BY_DEPARTED.equals(decision.reason())) {
                throw new ApiException(HttpStatus.CONFLICT, "DEPARTED_RESERVATION_BLOCKS",
                        "窗口存在已起飞预留，不可挤出且容量不足");
            }
            throw new ApiException(HttpStatus.CONFLICT, "WINDOW_CAPACITY_CONFLICT",
                    "窗口重叠且无法合法挤出同级或更高优先级预留");
        }

        long now = nowMillis();
        String newReservationId = "rsv_" + UUID.randomUUID();
        ReservationPo newReservation = new ReservationPo(newReservationId,
                request.airportId(), request.routeId(), request.priority(),
                request.windowStart(), request.windowEnd(),
                ReservationPo.STATUS_ACTIVE, false, request.requestId(), now);
        reservationRepo.insertReservation(newReservation);

        List<String> displacedIds = new ArrayList<>();
        List<ReplacementDto> replacements = new ArrayList<>();
        for (ReservationPo victim : decision.displaced()) {
            // 持锁裁决出的候选必为 ACTIVE 且未起飞；条件更新 0 行说明状态被并发破坏，
            // 不可能在机场行锁保护下发生，兜底按冲突回滚
            int updated = reservationRepo.markDisplaced(victim.reservationId());
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_CONFLICT",
                        "并发状态变化，请使用相同 requestId 与参数重试");
            }
            ReplacementPo event = new ReplacementPo("rpl_" + UUID.randomUUID(),
                    request.airportId(), victim.reservationId(), victim.routeId(),
                    victim.priority(), newReservationId, request.routeId(),
                    request.priority(), victim.windowStart(), victim.windowEnd(), now);
            reservationRepo.insertReplacement(event);
            displacedIds.add(victim.reservationId());
            replacements.add(toReplacementDto(event));
        }

        ReservationResultDto result = new ReservationResultDto(newReservationId,
                request.airportId(), request.routeId(), request.priority(),
                request.windowStart(), request.windowEnd(),
                ReservationPo.STATUS_ACTIVE, false,
                List.copyOf(displacedIds), List.copyOf(replacements));
        return new MutationResponse(request.requestId(), false, result);
    }

    /**
     * 登记预留起飞。仍 ACTIVE 的预留才能起飞；已被挤出不能起飞（409）。
     * 起飞与预留申请同样经机场行锁串行化；起飞后占用保留且不可被任何申请挤出。
     */
    public MutationResponse depart(ReservationDepartRequest request) {
        return idempotency.execute(request.requestId(), KIND_DEPART,
                idempotency.canonicalHash(request), () -> {
                    ReservationPo reservation =
                            reservationRepo.findReservation(request.reservationId());
                    if (reservation == null) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND",
                                "预留不存在: " + request.reservationId());
                    }
                    // 锁机场行使起飞与预留裁决互斥，并在锁内重读最新状态
                    AirportPo airport = reservationRepo.lockAirport(reservation.airportId());
                    if (airport == null) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "AIRPORT_NOT_FOUND",
                                "备降机场不存在: " + reservation.airportId());
                    }
                    ReservationPo fresh =
                            reservationRepo.findReservation(request.reservationId());
                    if (fresh != null && fresh.departed()) {
                        throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_ALREADY_DEPARTED",
                                "预留已起飞: " + request.reservationId());
                    }
                    if (fresh != null
                            && ReservationPo.STATUS_DISPLACED.equals(fresh.status())) {
                        throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_DISPLACED",
                                "预留已被挤出，不能起飞: " + request.reservationId());
                    }
                    int updated = reservationRepo.markDeparted(request.reservationId());
                    if (updated == 0) {
                        throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_NOT_ACTIVE",
                                "预留当前不是可起飞的 ACTIVE 状态: " + request.reservationId());
                    }
                    return new MutationResponse(request.requestId(), false,
                            new ReservationStateDto(request.reservationId(),
                                    reservation.airportId(), reservation.routeId(),
                                    ReservationPo.STATUS_ACTIVE, true));
                });
    }

    // ============================ 查询 ============================

    /**
     * 按机场与 UTC 左闭右开时段 [from, to) 查询当前占用与不可变替换链。
     * 占用列表只含当前 ACTIVE 预留（含已起飞）；替换链为被挤出窗口与该时段相交的
     * 全部不可变事件，后续起飞等状态变化不改写旧事件。
     */
    public OccupancyReportDto queryOccupancy(String airportId, long from, long to) {
        if (from >= to) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_WINDOW",
                    "查询时段必须为 UTC 左闭右开非空区间：from < to");
        }
        return txTemplate.execute(status -> {
            AirportPo airport = reservationRepo.findAirport(airportId);
            if (airport == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "AIRPORT_NOT_FOUND",
                        "备降机场不存在: " + airportId);
            }
            List<ReservationPo> reservations =
                    reservationRepo.findReservationsOverlapping(airportId, from, to);
            List<OccupancyDto> occupancies = new ArrayList<>();
            for (ReservationPo po : reservations) {
                if (ReservationPo.STATUS_ACTIVE.equals(po.status())) {
                    occupancies.add(new OccupancyDto(po.reservationId(), po.routeId(),
                            po.priority(), po.windowStart(), po.windowEnd(),
                            po.status(), po.departed()));
                }
            }
            // 不可变替换链：被挤出预留窗口与查询时段相交的事件，按机场读取后在内存过滤，
            // 事件窗口为挤出时的快照窗口，永不变化
            List<ReplacementDto> chain = new ArrayList<>();
            for (ReservationPo po : reservations) {
                for (ReplacementPo event :
                        reservationRepo.findReplacementsByReplaced(po.reservationId())) {
                    if (DisplacementPlanner.overlaps(from, to, event.windowStart(), event.windowEnd())) {
                        chain.add(toReplacementDto(event));
                    }
                }
            }
            chain.sort((a, b) -> a.createdAt() == b.createdAt()
                    ? a.replacementId().compareTo(b.replacementId())
                    : Long.compare(a.createdAt(), b.createdAt()));
            return new OccupancyReportDto(airportId, from, to,
                    List.copyOf(occupancies), List.copyOf(chain));
        });
    }

    // ============================ 工具方法 ============================

    private long nowMillis() {
        return clock.instant().toEpochMilli();
    }

    private static ReplacementDto toReplacementDto(ReplacementPo po) {
        return new ReplacementDto(po.replacementId(), po.airportId(),
                po.replacedReservationId(), po.replacedRouteId(), po.replacedPriority(),
                po.replacementReservationId(), po.replacementRouteId(),
                po.replacementPriority(), po.windowStart(), po.windowEnd(), po.createdAt());
    }
}
