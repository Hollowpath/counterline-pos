package com.counterline.pos.orders;

import com.counterline.pos.auth.TokenService;
import com.counterline.pos.customers.CustomerService;
import com.counterline.pos.orders.OrderDtos.CustomerAnalytics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lives in the orders package because analytics are derived from orders; orders already depend
 * on customers, so putting this in the customers package would create a cycle.
 */
@RestController
@SecurityRequirement(name = "bearer")
class CustomerAnalyticsController {

    private final CustomerService customerService;
    private final OrderQueryService orderQueries;

    CustomerAnalyticsController(CustomerService customerService, OrderQueryService orderQueries) {
        this.customerService = customerService;
        this.orderQueries = orderQueries;
    }

    @Operation(summary = "Order count, total, average, last order date and top 5 items of a customer",
            description = "Counts paid orders that were not refunded.")
    @GetMapping("/api/v1/customers/{customerId}/analytics")
    CustomerAnalytics analytics(@PathVariable Long customerId, @AuthenticationPrincipal Jwt jwt) {
        // A deactivated member keeps their history, but only the dashboard sees it; a till gets 404, as for any unknown customer.
        if (TokenService.isDashboardSession(jwt)) {
            customerService.getIncludingInactive(customerId);
        } else {
            customerService.get(customerId);
        }
        return orderQueries.customerAnalytics(customerId);
    }
}
