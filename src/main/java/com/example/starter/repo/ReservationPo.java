package com.example.starter.repo;

/**
 * 备降预留 PO。
 *
 * @param reservationId 预留唯一标识（不可变）
 * @param airportId     备降机场标识
 * @param routeId       申请预留的航线标识
 * @param priority      优先级，数值越大越高
 * @param windowStart   窗口起点，epoch 毫秒（UTC），左闭
 * @param windowEnd     窗口终点，epoch 毫秒（UTC），右开
 * @param status        ACTIVE 占用容量；DISPLACED 已被挤出、不再占用
 * @param departed      是否已起飞；TRUE 时占用保留且不可被挤出
 * @param requestId     创建预留的幂等请求标识
 * @param createdAt     创建时间，epoch 毫秒（UTC）
 */
public record ReservationPo(String reservationId, String airportId, String routeId, int priority,
                            long windowStart, long windowEnd, String status, boolean departed,
                            String requestId, long createdAt) {

    /** 预留状态：占用窗口容量。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 预留状态：已被高优先级整体挤出，不再占用容量。 */
    public static final String STATUS_DISPLACED = "DISPLACED";
}
