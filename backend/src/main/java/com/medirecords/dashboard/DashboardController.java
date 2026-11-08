package com.medirecords.dashboard;

import com.medirecords.auth.Role;
import com.medirecords.dashboard.DashboardDtos.DashboardResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Readable by every signed-in user (it is a GET, see {@code SecurityConfig}). The response is the same
 * for both roles except that only ADMIN gets {@code recentActivity}, which comes from the ADMIN-only
 * audit log; for viewers that query is not even run.
 */
@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "Dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Summary figures for the home page",
            description = "Patient totals and counts per blood type, imports in the last 7 days with their "
                    + "rejected rows, and the latest imports. ADMIN responses also include recentActivity "
                    + "(latest data changes from the audit log); for VIEWER the field is absent.")
    public DashboardResponse get(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> Role.ADMIN.authority().equals(authority.getAuthority()));
        return service.summary(admin);
    }
}
