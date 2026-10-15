package com.quantgen.ledger.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.quantgen.ledger.domain.Direction;
import com.quantgen.ledger.domain.JournalType;

/**
 * A balanced set of ledger lines describing one business event. Build with {@link #builder}.
 *
 * @param createdBy the acting user, or null for system jobs
 */
public record JournalRequest(JournalType type, String description, UUID createdBy, List<Line> lines) {

    public JournalRequest {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(description, "description");
        lines = List.copyOf(lines);
    }

    /** @param counterparty a short, display-safe label shown in transaction history (never a full account number) */
    public record Line(UUID ledgerAccountId, Direction direction, BigDecimal amount, String counterparty) {

        public Line {
            Objects.requireNonNull(ledgerAccountId, "ledgerAccountId");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(amount, "amount");
        }
    }

    public static Builder builder(JournalType type, String description, UUID createdBy) {
        return new Builder(type, description, createdBy);
    }

    public static final class Builder {
        private final JournalType type;
        private final String description;
        private final UUID createdBy;
        private final List<Line> lines = new ArrayList<>();

        private Builder(JournalType type, String description, UUID createdBy) {
            this.type = type;
            this.description = description;
            this.createdBy = createdBy;
        }

        public Builder debit(UUID ledgerAccountId, BigDecimal amount, String counterparty) {
            lines.add(new Line(ledgerAccountId, Direction.DEBIT, amount, counterparty));
            return this;
        }

        public Builder credit(UUID ledgerAccountId, BigDecimal amount, String counterparty) {
            lines.add(new Line(ledgerAccountId, Direction.CREDIT, amount, counterparty));
            return this;
        }

        public JournalRequest build() {
            return new JournalRequest(type, description, createdBy, lines);
        }
    }
}
