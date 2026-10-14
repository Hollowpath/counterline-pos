package com.counterline.pos.reports;

import com.counterline.pos.auth.Terminal;
import com.counterline.pos.reports.ReportDtos.CategorySales;
import com.counterline.pos.reports.ReportDtos.Discounts;
import com.counterline.pos.reports.ReportDtos.HourlySales;
import com.counterline.pos.reports.ReportDtos.ItemSales;
import com.counterline.pos.reports.ReportDtos.LoyaltyPoints;
import com.counterline.pos.reports.ReportDtos.Modifiers;
import com.counterline.pos.reports.ReportDtos.PaymentMethods;
import com.counterline.pos.reports.ReportDtos.SalesSummary;
import com.counterline.pos.reports.ReportDtos.Timecards;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Reports for the terminal's outlet over a date range of whole business days (outlet time zone),
 * e.g. ?from=2026-10-12&to=2026-10-12.
 */
@RestController
@RequestMapping("/api/v1/reports")
@SecurityRequirement(name = "bearer")
class ReportController {

    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
    }

    @Operation(summary = "Gross sales, refunds, net sales and totals per payment method")
    @GetMapping("/sales-summary")
    SalesSummary salesSummary(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                              @AuthenticationPrincipal Jwt jwt) {
        return reports.salesSummary(Terminal.of(jwt).outletId(), from, to);
    }

    @Operation(summary = "Payments and amount per payment method, less refunds (the printed Payment Methods report)")
    @GetMapping("/payment-methods")
    PaymentMethods paymentMethods(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  @AuthenticationPrincipal Jwt jwt) {
        return reports.paymentMethods(Terminal.of(jwt).outletId(), from, to);
    }

    @Operation(summary = "Loyalty points issued and redeemed (the printed Loyalty Points report)")
    @GetMapping("/loyalty-points")
    LoyaltyPoints loyaltyPoints(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @AuthenticationPrincipal Jwt jwt) {
        return reports.loyaltyPoints(Terminal.of(jwt).outletId(), from, to);
    }

    @Operation(summary = "Sales by hour of the day")
    @GetMapping("/hourly-sales")
    HourlySales hourlySales(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @AuthenticationPrincipal Jwt jwt) {
        return reports.hourlySales(Terminal.of(jwt).outletId(), from, to);
    }

    @Operation(summary = "Quantity and amount sold per item, largest first")
    @GetMapping("/item-sales")
    ItemSales itemSales(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @AuthenticationPrincipal Jwt jwt) {
        return reports.itemSales(Terminal.of(jwt).outletId(), from, to);
    }

    @Operation(summary = "Sales per category (as categorised when sold), with its items")
    @GetMapping("/category-sales")
    CategorySales categorySales(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @AuthenticationPrincipal Jwt jwt) {
        return reports.categorySales(Terminal.of(jwt).outletId(), from, to);
    }

    @Operation(summary = "Amount taken off per discount")
    @GetMapping("/discounts")
    Discounts discounts(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @AuthenticationPrincipal Jwt jwt) {
        return reports.discounts(Terminal.of(jwt).outletId(), from, to);
    }

    @Operation(summary = "How often each item option was picked")
    @GetMapping("/modifiers")
    Modifiers modifiers(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @AuthenticationPrincipal Jwt jwt) {
        return reports.modifiers(Terminal.of(jwt).outletId(), from, to);
    }

    @Operation(summary = "Clock in/out events")
    @GetMapping("/timecards")
    Timecards timecards(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @AuthenticationPrincipal Jwt jwt) {
        return reports.timecards(Terminal.of(jwt).outletId(), from, to);
    }
}
