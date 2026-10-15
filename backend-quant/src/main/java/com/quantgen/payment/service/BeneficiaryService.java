package com.quantgen.payment.service;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.account.service.AccountRef;
import com.quantgen.account.service.AccountService;
import com.quantgen.auth.service.UserService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.payment.api.PaymentDtos.BeneficiaryDto;
import com.quantgen.payment.api.PaymentDtos.BeneficiaryRequest;
import com.quantgen.payment.domain.Beneficiary;
import com.quantgen.payment.domain.BeneficiaryRepository;

/** F-PAY-4: saved beneficiaries, validated against a real account on save. */
@Service
public class BeneficiaryService {

    static final int MAX_BENEFICIARIES = 50;

    private final BeneficiaryRepository beneficiaries;
    private final AccountService accounts;
    private final UserService users;

    public BeneficiaryService(BeneficiaryRepository beneficiaries, AccountService accounts, UserService users) {
        this.beneficiaries = beneficiaries;
        this.accounts = accounts;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<BeneficiaryDto> list(UUID userId) {
        return beneficiaries.findByUserIdOrderByNicknameAsc(userId).stream().map(BeneficiaryService::toDto).toList();
    }

    @Transactional
    public BeneficiaryDto add(UUID userId, BeneficiaryRequest req) {
        AccountRef target = accounts.findByNumber(req.accountNumber())
                .orElseThrow(() -> ApiException.fieldError(HttpStatus.BAD_REQUEST, "accountNumber",
                        "no account with this number"));
        if (target.userId().equals(userId)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "accountNumber",
                    "is one of your own accounts; transfer to it directly");
        }
        if (beneficiaries.existsByUserIdAndAccountNumber(userId, req.accountNumber())) {
            throw duplicate();
        }
        if (beneficiaries.countByUserId(userId) >= MAX_BENEFICIARIES) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "You can save at most " + MAX_BENEFICIARIES + " beneficiaries");
        }
        String holderName = users.getUser(target.userId()).fullName();
        try {
            return toDto(beneficiaries.saveAndFlush(new Beneficiary(userId, target.id(), req.nickname().trim(),
                    target.accountNumber(), holderName)));
        } catch (DataIntegrityViolationException e) {
            throw duplicate();
        }
    }

    @Transactional
    public void delete(UUID userId, UUID beneficiaryId) {
        Beneficiary b = beneficiaries.findByIdAndUserId(beneficiaryId, userId)
                .orElseThrow(() -> ApiException.notFound("Beneficiary"));
        beneficiaries.delete(b);
    }

    private static ApiException duplicate() {
        return ApiException.fieldError(HttpStatus.CONFLICT, "accountNumber", "is already a saved beneficiary");
    }

    static BeneficiaryDto toDto(Beneficiary b) {
        return new BeneficiaryDto(b.getId(), b.getNickname(), b.getAccountNumber(), b.getHolderName(), b.getCreatedAt());
    }
}
