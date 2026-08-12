package com.payflow.payment.api;

import com.payflow.payment.api.dto.TransactionResponse;
import com.payflow.payment.api.dto.WalletResponse;
import com.payflow.payment.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
@Tag(name = "Wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping
    @Operation(summary = "List customer wallets")
    public List<WalletResponse> list() {
        return walletService.listWallets();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a wallet by id")
    public WalletResponse get(@PathVariable UUID id) {
        return walletService.getWallet(id);
    }

    @GetMapping("/{id}/transactions")
    @Operation(summary = "List a wallet's ledger entries, newest first")
    public List<TransactionResponse> transactions(@PathVariable UUID id) {
        return walletService.listTransactions(id);
    }
}
