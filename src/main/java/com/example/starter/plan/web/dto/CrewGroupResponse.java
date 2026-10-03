package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 乘务组响应，成员按业务键升序返回。
 */
public record CrewGroupResponse(String groupCode, List<String> memberCodes) {
}