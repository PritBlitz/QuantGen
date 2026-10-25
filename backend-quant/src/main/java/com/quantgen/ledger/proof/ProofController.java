package com.quantgen.ledger.proof;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.common.error.ApiException;
import com.quantgen.common.security.AuthUser;
import com.quantgen.ledger.proof.BalanceSnapshotService.ProofDto;
import com.quantgen.ledger.proof.BalanceSnapshotService.RootDto;
import com.quantgen.ledger.proof.BalanceSnapshotService.SolvencyDto;
import com.quantgen.ledger.proof.ChainVerifier.ChainReport;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD 12.10 Proof of Balance. /admin/** is role ADMIN and /public/proofs/roots is open (SecurityConfig). */
@RestController
@Tag(name = "Proof of Balance")
public class ProofController {

    private final BalanceSnapshotService snapshots;
    private final ProofJobs jobs;

    public ProofController(BalanceSnapshotService snapshots, ProofJobs jobs) {
        this.snapshots = snapshots;
        this.jobs = jobs;
    }

    @GetMapping("/api/v1/public/proofs/roots")
    @Operation(summary = "Published daily Merkle sum tree roots (newest first, last 30). No sign-in needed.")
    public ResponseEntity<List<RootDto>> roots() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(snapshots.roots());
    }

    @GetMapping("/api/v1/proofs/accounts/{accountId}")
    @Operation(summary = "Inclusion proof of one of your accounts in a snapshot (default the latest). 404 when the "
            + "date has no snapshot or the account isn't in it.")
    public ProofDto proof(@AuthenticationPrincipal AuthUser user, @PathVariable UUID accountId,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return snapshots.proof(user.id(), accountId, date);
    }

    @GetMapping("/api/v1/admin/ledger/verify-chain")
    @Operation(summary = "Recompute the seal chain and report the first broken position, if any. from=GENESIS "
            + "(default) checks everything; from=CHECKPOINT only what was sealed after the last intact run")
    public ChainReport verifyChain(@AuthenticationPrincipal AuthUser admin,
                                   @RequestParam(defaultValue = "GENESIS") String from) {
        if (!from.equals("GENESIS") && !from.equals("CHECKPOINT")) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "from", "must be GENESIS or CHECKPOINT");
        }
        return jobs.verifyAndRecord(admin.id(), from.equals("CHECKPOINT"));
    }

    @GetMapping("/api/v1/admin/ledger/solvency")
    @Operation(summary = "Live customer liabilities against simulated reserves (cash, loan book, gateway receivables)")
    public SolvencyDto solvency() {
        return snapshots.solvency();
    }
}
