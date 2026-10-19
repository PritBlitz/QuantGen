package com.quantgen.risk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.quantgen.common.audit.AuditService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * F-RISK-4: up to 3 documents (PDF, PNG or JPEG by magic bytes, ≤ 5 MB) per pending loan application, with
 * explicit consent to AI processing. The bytes are stored on disk under a random name and are never served back;
 * the DB keeps the metadata and a SHA-256. The Underwriting Analyst (V2-M4) reads them; until then the
 * underwriting view is PENDING.
 */
@Service
public class LoanDocumentService {

    private static final Logger log = LoggerFactory.getLogger(LoanDocumentService.class);

    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final int MAX_PER_LOAN = 3;

    public record LoanDocumentDto(UUID id, String fileName, String contentType, long sizeBytes, Instant uploadedAt) {
    }

    public record Extracted(String field, String value, int page) {
    }

    public record UnderwritingDto(String status, String memo, List<Extracted> extracted, List<String> flags,
                                  String recommendation, Instant generatedAt) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;
    private final Path dir;

    LoanDocumentService(JdbcTemplate jdbc, AuditService audit, JsonMapper json, Clock clock,
                        @Value("${qg.storage.loan-documents-dir:${java.io.tmpdir}/quantgen/loan-documents}") String dir) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.dir = Path.of(dir);
    }

    @Transactional
    public LoanDocumentDto upload(UUID userId, UUID loanId, MultipartFile file, boolean consent) {
        // Lock the loan so two uploads can't both be the third document.
        List<String> status = jdbc.queryForList("SELECT status FROM loans WHERE id = ? AND user_id = ? FOR UPDATE",
                String.class, loanId, userId);
        if (status.isEmpty()) {
            throw ApiException.notFound("Loan");
        }
        if (!consent) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "consent", "consent to AI processing is required");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "file", "attach a file");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "file", "must be 5 MB or smaller");
        }
        if (!"PENDING".equals(status.getFirst())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Documents can only be added while the application is pending");
        }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM loan_documents WHERE loan_id = ?", Integer.class, loanId);
        if (count != null && count >= MAX_PER_LOAN) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "At most " + MAX_PER_LOAN + " documents per application");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "file", "could not be read");
        }
        String type = sniff(bytes);
        if (type == null) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "file", "must be a PDF, PNG or JPEG");
        }
        String key = UUID.randomUUID().toString(); // no extension, no user-chosen part
        Path target = store(key, bytes);
        // If the row doesn't commit, don't leave the bytes behind.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int completion) {
                if (completion != STATUS_COMMITTED) {
                    deleteQuietly(target);
                }
            }
        });
        String name = safeName(file.getOriginalFilename());
        UUID id = jdbc.queryForObject("""
                INSERT INTO loan_documents (loan_id, user_id, file_name, content_type, size_bytes, sha256, storage_key, consent_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, loanId, userId, name, type, (long) bytes.length, sha256(bytes), key,
                Timestamp.from(clock.instant()));
        audit.record(userId, "LOAN_DOCUMENT_UPLOADED", "LOAN", loanId.toString(), type + ", " + bytes.length + " bytes");
        return list(loanId).stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    /** The owner's own loan, or any loan for an admin; 404 otherwise. */
    @Transactional(readOnly = true)
    public List<LoanDocumentDto> listFor(UUID userId, boolean admin, UUID loanId) {
        Boolean visible = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM loans WHERE id = ? AND (? OR user_id = ?))",
                Boolean.class, loanId, admin, userId);
        if (!Boolean.TRUE.equals(visible)) {
            throw ApiException.notFound("Loan");
        }
        return list(loanId);
    }

    @Transactional(readOnly = true)
    public UnderwritingDto underwriting(UUID loanId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM loans WHERE id = ?)", Boolean.class, loanId);
        if (!Boolean.TRUE.equals(exists)) {
            throw ApiException.notFound("Loan");
        }
        List<UnderwritingDto> memo = jdbc.query("SELECT * FROM underwriting_memos WHERE loan_id = ?", (rs, i) -> {
            Timestamp at = rs.getTimestamp("generated_at");
            return new UnderwritingDto(rs.getString("status"), rs.getString("memo"),
                    json.readValue(rs.getString("extracted"), new TypeReference<List<Extracted>>() { }),
                    json.readValue(rs.getString("flags"), new TypeReference<List<String>>() { }),
                    rs.getString("recommendation"), at == null ? null : at.toInstant());
        }, loanId);
        if (!memo.isEmpty()) {
            return memo.getFirst();
        }
        boolean hasDocs = !list(loanId).isEmpty();
        return new UnderwritingDto("PENDING", null, List.of(),
                List.of(hasDocs ? "Waiting for the Underwriting Analyst." : "No documents uploaded yet."), null, null);
    }

    private List<LoanDocumentDto> list(UUID loanId) {
        return jdbc.query("SELECT id, file_name, content_type, size_bytes, uploaded_at FROM loan_documents "
                        + "WHERE loan_id = ? ORDER BY uploaded_at, id",
                (rs, i) -> new LoanDocumentDto(rs.getObject("id", UUID.class), rs.getString("file_name"),
                        rs.getString("content_type"), rs.getLong("size_bytes"), rs.getTimestamp("uploaded_at").toInstant()),
                loanId);
    }

    /** By content, never by the client's Content-Type or file name. */
    static String sniff(byte[] b) {
        if (b.length >= 4 && b[0] == 0x25 && b[1] == 0x50 && b[2] == 0x44 && b[3] == 0x46) {
            return "application/pdf";
        }
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 0x50 && b[2] == 0x4e && b[3] == 0x47
                && b[4] == 0x0d && b[5] == 0x0a && b[6] == 0x1a && b[7] == 0x0a) {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        return null;
    }

    /** Display only: letters, digits, dot, dash, underscore and space; at most 80 characters. */
    static String safeName(String original) {
        String path = original == null ? "" : original.replace('\\', '/');
        String base = path.substring(path.lastIndexOf('/') + 1); // drop any client-side directories
        String cleaned = base.replaceAll("[^\\w.\\- ]+", "_").strip();
        if (cleaned.isEmpty() || cleaned.chars().allMatch(c -> c == '.')) {
            cleaned = "document";
        }
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private Path store(String key, byte[] bytes) {
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "upload-", ".part");
            Files.write(tmp, bytes);
            Path target = dir.resolve(key);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            log.error("Could not store a loan document", e);
            throw new ApiException(ErrorCode.INVALID_STATE, "The document could not be stored; try again");
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Could not delete an orphaned loan document {}", p.getFileName());
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
