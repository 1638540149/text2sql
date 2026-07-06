package com.text2sql.controller;

import com.text2sql.mapper.CoreMapper;
import com.text2sql.service.QueryService;
import com.text2sql.service.QueryService.QueryRequest;
import com.text2sql.util.CurrentUser;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class QueryController {
    private final QueryService queryService;
    private final CoreMapper mapper;

    public QueryController(QueryService queryService, CoreMapper mapper) {
        this.queryService = queryService;
        this.mapper = mapper;
    }

    @PostMapping("/query/run")
    public Map<String, Object> run(@RequestBody QueryRequest request) throws Exception {
        return queryService.run(request, CurrentUser.get());
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history() {
        var user = CurrentUser.get();
        return mapper.listHistory(user.getId(), user.isAdmin());
    }

    @PostMapping("/history/{id}/feedback")
    public Map<String, Object> feedback(@PathVariable Long id, @RequestBody FeedbackRequest request) {
        var user = CurrentUser.get();
        int updated = mapper.updateFeedback(id, user.getId(), user.isAdmin(), request.score(),
            String.join(",", request.tags() == null ? Collections.emptyList() : request.tags()), request.comment());
        return Map.of("success", updated > 0);
    }

    @GetMapping("/admin/analytics/models")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> analytics(@RequestParam(required = false) Long modelId,
                                         @RequestParam(required = false) Long dataSourceId,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to) {
        return queryService.analytics(modelId, dataSourceId, from, to);
    }

    public record FeedbackRequest(int score, List<String> tags, String comment) {}
}
