package com.quantgen.ledger.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.ledger.service.LedgerService;
import com.quantgen.ledger.service.ReconcileReport;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** F-LED-8 reconciliation (role ADMIN, enforced for /api/v1/admin/** in SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/ledger")
@Tag(name = "Admin")
public class LedgerAdminController {

    private final LedgerService ledger;

    public LedgerAdminController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/reconcile")
    @Operation(summary = "Check every cached balance against its lines and that the ledger sums to zero")
    public ReconcileReport reconcile() {
        return ledger.reconcile();
    }
}
