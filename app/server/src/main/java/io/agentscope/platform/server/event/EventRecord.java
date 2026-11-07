package io.agentscope.platform.server.event;

import java.util.Map;

/** 一条已落库的事件。`id` 就是 SSE 的 cursor。 */
public record EventRecord(long id, long conversationId, long turnId, int seq, String type,
                          Map<String, Object> payload) {}
