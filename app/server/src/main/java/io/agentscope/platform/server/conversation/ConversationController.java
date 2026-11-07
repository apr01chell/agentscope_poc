package io.agentscope.platform.server.conversation;

import io.agentscope.platform.common.ApiResult;
import io.agentscope.platform.server.security.CurrentUser;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 会话与轮次的 HTTP 入口。归属校验统一走 {@link CurrentUser}。 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationRepository repository;
    private final TurnService turns;
    private final CurrentUser currentUser;

    public ConversationController(
            ConversationRepository repository, TurnService turns, CurrentUser currentUser) {
        this.repository = repository;
        this.turns = turns;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list() {
        return ApiResult.ok(repository.listOwned(currentUser.tenant(), currentUser.sub()));
    }

    @PostMapping
    public ApiResult<Map<String, Object>> create(@RequestBody CreateConversation request) {
        String title = (request.title() == null || request.title().isBlank())
                ? "新会话" : request.title().trim();
        long id = repository.create(currentUser.tenant(), currentUser.sub(), title);
        return ApiResult.ok(Map.of("id", id, "title", title));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResult<Map<String, Object>>> get(@PathVariable long id) {
        return repository.find(id)
                .filter(row -> ownsConversation(row))
                .map(row -> ResponseEntity.ok(ApiResult.ok(Map.of(
                        "id", row.get("id"),
                        "title", row.get("title"),
                        "turns", turns.turns(id)))))
                // 别人的会话一律 404，不泄露"存在但不是你的"
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResult.failure("会话不存在", "NOT_FOUND")));
    }

    @PostMapping("/{id}/turns")
    public ResponseEntity<ApiResult<Map<String, Object>>> submitTurn(
            @PathVariable long id, @RequestBody SubmitTurn request) {
        if (request.input() == null || request.input().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResult.failure("内容不能为空", "INVALID_INPUT"));
        }
        if (repository.find(id).filter(this::ownsConversation).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResult.failure("会话不存在", "NOT_FOUND"));
        }
        TurnService.SubmittedTurn submitted = turns.submit(id, request.input());
        return ResponseEntity.accepted().body(ApiResult.ok(Map.of(
                "turnId", submitted.turnId(),
                "cursor", submitted.cursor())));
    }

    private boolean ownsConversation(Map<String, Object> row) {
        return currentUser.sub().equals(row.get("ownerSub"))
                && currentUser.tenant().equals(row.get("tenant"));
    }

    public record CreateConversation(String title) {}

    public record SubmitTurn(String input) {}
}
