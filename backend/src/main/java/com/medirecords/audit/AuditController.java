package com.medirecords.audit;

import com.medirecords.audit.AuditDtos.AuditEntryResponse;
import com.medirecords.audit.AuditDtos.AuditFilter;
import com.medirecords.common.web.BadRequestException;
import com.medirecords.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit log is ADMIN-only, reads included: it holds the old and new values of every change. This
 * is the one GET that needs more than the "any signed-in user may read" rule in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/audit-log")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Audit log")
public class AuditController {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Audit entries, newest first, with optional filters (ADMIN)",
            description = "from/to are ISO-8601 instants (e.g. 2026-11-08T00:00:00Z); from is inclusive, "
                    + "to exclusive. Pages are 0-based; max page size 100.")
    public PageResponse<AuditEntryResponse> list(
            @Parameter(description = "Exact username") @RequestParam(required = false) @Size(max = 50) String actor,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BadRequestException("'from' must be before 'to'.");
        }
        AuditFilter filter = new AuditFilter(StringUtils.hasText(actor) ? actor.trim() : null, action, from, to);
        return PageResponse.from(service.search(filter,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST)));
    }

    @GetMapping("/actors")
    @Operation(summary = "Usernames that appear in the audit log, for the user filter (ADMIN)")
    public List<String> actors() {
        return service.actors();
    }
}
