package com.payflow.payment.api;

import com.payflow.payment.api.dto.WalletResponse;
import com.payflow.payment.service.DemoDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/demo")
@Tag(name = "Demo")
public class DemoController {

    private final DemoDataService demoDataService;

    public DemoController(DemoDataService demoDataService) {
        this.demoDataService = demoDataService;
    }

    @PostMapping("/reset")
    @Operation(summary = "Wipe and reseed demo data (admin)",
            description = "Requires an ADMIN bearer token from /api/v1/auth/login. Returns the freshly seeded customer wallets.")
    @SecurityRequirement(name = "bearerAuth")
    public List<WalletResponse> reset() {
        return demoDataService.reset();
    }
}
