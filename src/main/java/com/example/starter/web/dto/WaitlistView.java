package com.example.starter.web.dto;

import java.util.List;

/**
 * 候补队列视图，按申请时间、申请编号升序排列。
 */
public record WaitlistView(String instrumentId, List<RequestView> waiting) {
}
