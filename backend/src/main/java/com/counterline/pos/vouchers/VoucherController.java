package com.counterline.pos.vouchers;

import com.counterline.pos.vouchers.VoucherService.BatchRequest;
import com.counterline.pos.vouchers.VoucherService.BatchSummary;
import com.counterline.pos.vouchers.VoucherService.CreatedBatch;
import com.counterline.pos.vouchers.VoucherService.VoucherRow;
import com.counterline.pos.vouchers.VoucherService.VoucherView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@SecurityRequirement(name = "bearer")
class VoucherController {

    private final VoucherService vouchers;

    VoucherController(VoucherService vouchers) {
        this.vouchers = vouchers;
    }

    @Operation(summary = "Look up a voucher keyed in at the till: its value, expiry and whether it can be used")
    @GetMapping("/api/v1/vouchers/{code}")
    VoucherView check(@PathVariable String code) {
        return vouchers.check(code);
    }

    @Operation(summary = "Dashboard, head office: voucher batches with how many were used")
    @GetMapping("/api/v1/dashboard/vouchers")
    List<BatchSummary> batches() {
        return vouchers.batches();
    }

    @Operation(summary = "Dashboard, head office: issue a batch of vouchers (returns the codes to print)")
    @PostMapping("/api/v1/dashboard/vouchers")
    @ResponseStatus(HttpStatus.CREATED)
    CreatedBatch createBatch(@Valid @RequestBody BatchRequest request, @AuthenticationPrincipal Jwt jwt) {
        return vouchers.createBatch(request, jwt.getClaimAsString("name"));
    }

    @Operation(summary = "Dashboard, head office: a batch's vouchers and whether each was used")
    @GetMapping("/api/v1/dashboard/vouchers/{batchId}")
    List<VoucherRow> vouchers(@PathVariable Long batchId) {
        return vouchers.vouchers(batchId);
    }
}
