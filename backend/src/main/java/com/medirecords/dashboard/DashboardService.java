package com.medirecords.dashboard;

import com.medirecords.audit.AuditAction;
import com.medirecords.dashboard.DashboardDtos.BloodTypeCount;
import com.medirecords.dashboard.DashboardDtos.DashboardResponse;
import com.medirecords.dashboard.DashboardDtos.PatientSummary;
import com.medirecords.dashboard.DashboardQueries.BloodTypeTotals;
import com.medirecords.patient.BloodType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

    /** "This week" is a rolling 7 days: it means the same thing whatever the reader's time zone or weekday. */
    static final Duration WINDOW = Duration.ofDays(7);
    static final int RECENT_IMPORTS = 5;
    static final int RECENT_ACTIVITY = 8;

    /** Data changes only: sign-ins would crowd out everything else (every dashboard visit follows one). */
    private static final List<AuditAction> ACTIVITY_ACTIONS =
            List.of(AuditAction.CREATE, AuditAction.UPDATE, AuditAction.DELETE, AuditAction.IMPORT);

    private final DashboardQueries queries;
    private final Clock clock;

    public DashboardService(DashboardQueries queries, Clock clock) {
        this.queries = queries;
        this.clock = clock;
    }

    /**
     * Four small queries (three for viewers) in one read-only transaction, so the figures come from one
     * consistent snapshot of the database.
     *
     * @param includeActivity whether to read the audit log; only true for administrators
     */
    @Transactional(readOnly = true)
    public DashboardResponse summary(boolean includeActivity) {
        Instant now = clock.instant();
        Instant since = now.minus(WINDOW);
        return new DashboardResponse(now, since,
                patients(queries.patientsByBloodType(since)),
                queries.importSummary(since),
                queries.recentImports(RECENT_IMPORTS),
                includeActivity ? queries.recentActivity(ACTIVITY_ACTIONS, RECENT_ACTIVITY) : null);
    }

    /** Totals are sums of the groups; the chart lists every blood type in a fixed order, zeros included. */
    private static PatientSummary patients(List<BloodTypeTotals> groups) {
        long total = groups.stream().mapToLong(BloodTypeTotals::count).sum();
        long added = groups.stream().mapToLong(BloodTypeTotals::addedSince).sum();
        Map<BloodType, Long> counts = groups.stream().filter(group -> group.bloodType() != null)
                .collect(Collectors.toMap(BloodTypeTotals::bloodType, BloodTypeTotals::count));
        List<BloodTypeCount> byBloodType = new ArrayList<>();
        for (BloodType type : BloodType.values()) {
            byBloodType.add(new BloodTypeCount(type, counts.getOrDefault(type, 0L)));
        }
        long unknown = groups.stream().filter(group -> group.bloodType() == null)
                .mapToLong(BloodTypeTotals::count).sum();
        if (unknown > 0) {
            byBloodType.add(new BloodTypeCount(null, unknown));
        }
        return new PatientSummary(total, added, List.copyOf(byBloodType));
    }
}
