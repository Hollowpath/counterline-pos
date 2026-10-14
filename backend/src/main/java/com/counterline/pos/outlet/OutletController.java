package com.counterline.pos.outlet;

import com.counterline.pos.outlet.OutletService.OutletResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public outlet details: the terminal set-up screen lists the outlets, and the PIN login screen
 * shows the one the terminal belongs to, before anyone is logged in.
 */
@RestController
@RequestMapping("/api/v1/outlets")
class OutletController {

    private final OutletService outletService;

    OutletController(OutletService outletService) {
        this.outletService = outletService;
    }

    @Operation(summary = "The chain's open outlets (no authentication required)")
    @GetMapping
    List<OutletResponse> list() {
        return outletService.activeOutlets();
    }

    @Operation(summary = "One outlet, for the login screen (no authentication required)")
    @GetMapping("/{id}")
    OutletResponse get(@PathVariable Long id) {
        return outletService.get(id);
    }
}
