package com.counterline.pos.dashboard;

import com.counterline.pos.auth.DashboardUser;
import com.counterline.pos.dashboard.DashboardService.LiveSales;
import com.counterline.pos.dashboard.SalesOverviewService.Overview;
import com.counterline.pos.outlet.OutletService.HistoricOutlet;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** The back-office dashboard: head office sees every outlet, a manager their own. */
@RestController
@RequestMapping("/api/v1/dashboard")
@SecurityRequirement(name = "bearer")
class DashboardController {

    private final DashboardService dashboard;
    private final SalesOverviewService overview;

    DashboardController(DashboardService dashboard, SalesOverviewService overview) {
        this.dashboard = dashboard;
        this.overview = overview;
    }

    @Operation(summary = "The outlets this user can see",
            description = "The open ones; with `includeClosed`, closed outlets too (marked `closed`), for reports and charts over a period.")
    @GetMapping("/outlets")
    List<HistoricOutlet> outlets(@RequestParam(defaultValue = "false") boolean includeClosed,
                                 @AuthenticationPrincipal Jwt jwt) {
        return dashboard.outlets(DashboardUser.of(jwt), includeClosed);
    }

    @Operation(summary = "Sales per outlet today (or on a date), with each outlet's latest orders",
            description = "Polled by the dashboard for live figures.")
    @GetMapping("/live")
    LiveSales live(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                   @AuthenticationPrincipal Jwt jwt) {
        return dashboard.live(DashboardUser.of(jwt), date);
    }

    @Operation(summary = "Sales over a period, for the charts",
            description = "From `from` to `to` (inclusive; the last 7 days by default), across the outlets this user "
                    + "can see or just `outletId`, with the period before for comparison.")
    @GetMapping("/overview")
    Overview overview(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                      @RequestParam(required = false) Long outletId, @AuthenticationPrincipal Jwt jwt) {
        return overview.overview(DashboardUser.of(jwt), from, to, outletId);
    }

    @Operation(summary = "One of an outlet's reports (the names of the POS Reports list)")
    @GetMapping("/outlets/{outletId}/reports/{name}")
    Object report(@PathVariable Long outletId, @PathVariable String name,
                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                  @AuthenticationPrincipal Jwt jwt) {
        return dashboard.report(DashboardUser.of(jwt), outletId, name, from, to);
    }
}
