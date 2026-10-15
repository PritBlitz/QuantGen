package com.quantgen.payment.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.common.security.AuthUser;
import com.quantgen.payment.api.PaymentDtos.BeneficiaryDto;
import com.quantgen.payment.api.PaymentDtos.BeneficiaryRequest;
import com.quantgen.payment.service.BeneficiaryService;

import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD §7.4 beneficiaries. */
@RestController
@RequestMapping("/api/v1/beneficiaries")
@Tag(name = "Payments")
public class BeneficiaryController {

    private final BeneficiaryService beneficiaries;

    public BeneficiaryController(BeneficiaryService beneficiaries) {
        this.beneficiaries = beneficiaries;
    }

    @GetMapping
    public List<BeneficiaryDto> list(@AuthenticationPrincipal AuthUser user) {
        return beneficiaries.list(user.id());
    }

    @PostMapping
    public ResponseEntity<BeneficiaryDto> add(@AuthenticationPrincipal AuthUser user,
                                              @Valid @RequestBody BeneficiaryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(beneficiaries.add(user.id(), request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        beneficiaries.delete(user.id(), id);
        return ResponseEntity.noContent().build();
    }
}
