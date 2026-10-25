package com.quantgen.trading.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.trading.service.CryptoPriceService;
import com.quantgen.trading.service.CryptoPriceService.CryptoPriceDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** F-TRD-12: read-only live crypto prices (PRD 12.9). No trading. */
@RestController
@Tag(name = "Trading")
public class CryptoController {

    private final CryptoPriceService crypto;

    public CryptoController(CryptoPriceService crypto) {
        this.crypto = crypto;
    }

    @GetMapping("/api/v1/market/crypto")
    @Operation(summary = "Live crypto prices in rupees from a free public feed (cached, read-only). 503 with the "
            + "crypto flag off, or before the first price arrived")
    public List<CryptoPriceDto> prices() {
        return crypto.prices();
    }
}
