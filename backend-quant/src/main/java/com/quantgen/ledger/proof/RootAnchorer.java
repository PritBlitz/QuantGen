package com.quantgen.ledger.proof;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.quantgen.ledger.proof.BalanceSnapshotService.RootDto;

import tools.jackson.databind.json.JsonMapper;

/**
 * F-POB-6: commits each published daily root to a git repository so anyone can later show the bank didn't rewrite
 * it (the commit history is the timestamp). Off unless {@code QG_ANCHOR_REPO_DIR} points at a clone of the public
 * repository the owner set up. Each root not yet anchored is written as {@code roots/<date>.json} (exactly what
 * {@code /public/proofs/roots} serves, minus the anchor) and committed. It never pushes: the owner pushes, and only
 * then is the anchor public. git runs with fixed argument lists, no shell; the file content is the bank's own JSON.
 */
@Component
class RootAnchorer {

    private static final Logger log = LoggerFactory.getLogger(RootAnchorer.class);
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final BalanceSnapshotService snapshots;
    private final JsonMapper json;
    private final Clock clock;
    private final String dir;
    private final String git;

    RootAnchorer(JdbcTemplate jdbc, BalanceSnapshotService snapshots, JsonMapper json, Clock clock,
                 @Value("${qg.proofs.anchor.repo-dir:}") String dir, @Value("${qg.proofs.anchor.git:git}") String git) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.json = json;
        this.clock = clock;
        this.dir = dir;
        this.git = git;
    }

    @Scheduled(fixedDelayString = "${qg.proofs.anchor.poll-millis:600000}", initialDelayString = "${qg.proofs.anchor.initial-millis:60000}")
    void poll() {
        if (dir.isBlank()) {
            return;
        }
        try {
            anchorPending();
        } catch (IOException | RuntimeException e) {
            log.warn("Anchoring proof roots failed: {}", e.getMessage());
        }
    }

    /** Anchors every published root that has no anchor yet, oldest first; returns how many it committed. */
    int anchorPending() throws IOException {
        Path repo = Path.of(dir);
        if (!Files.isDirectory(repo.resolve(".git"))) {
            throw new IOException("QG_ANCHOR_REPO_DIR is not a git clone");
        }
        List<RootDto> pending = new ArrayList<>(snapshots.roots().stream().filter(r -> r.anchor() == null)
                .filter(r -> !Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM proof_anchors WHERE snapshot_date = ?)", Boolean.class, r.date())))
                .toList());
        pending.sort((a, b) -> a.date().compareTo(b.date()));
        int done = 0;
        for (RootDto r : pending) {
            anchor(repo, r);
            done++;
        }
        return done;
    }

    private void anchor(Path repo, RootDto r) throws IOException {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("date", r.date().toString());
        file.put("root", r.root());
        file.put("totalLiabilities", r.totalLiabilities());
        file.put("leafCount", r.leafCount());
        file.put("chainHead", Map.of("position", r.chainHead().position(), "hash", r.chainHead().hash()));
        String rel = "roots/" + r.date() + ".json";
        Files.createDirectories(repo.resolve("roots"));
        Files.writeString(repo.resolve(rel), json.writerWithDefaultPrettyPrinter().writeValueAsString(file) + "\n",
                StandardCharsets.UTF_8);
        run(repo, git, "add", "--", rel);
        // Nothing staged means the file was already committed (e.g. a DB restored from backup): reuse that commit.
        if (run(repo, git, "diff", "--cached", "--quiet").exit() != 0) {
            run(repo, git, "-c", "user.name=QuantGen Proof of Balance", "-c", "user.email=proofs@quantgen.invalid",
                    "-c", "commit.gpgsign=false", "commit", "-q", "-m", "Proof of Balance root for " + r.date(), "--", rel);
        }
        String sha = run(repo, git, "log", "-n", "1", "--format=%H", "--", rel).out().strip();
        if (!sha.matches("^[0-9a-f]{40}$")) {
            throw new IOException("git didn't report a commit for " + rel);
        }
        jdbc.update("INSERT INTO proof_anchors (snapshot_date, commit_sha, committed_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (snapshot_date) DO NOTHING", r.date(), sha, Timestamp.from(clock.instant()));
        log.info("Anchored the proof root of {} in commit {}", r.date(), sha.substring(0, 12));
    }

    private record Result(int exit, String out) {
    }

    private static Result run(Path repo, String... cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        try {
            if (!p.waitFor(GIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("git timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("interrupted");
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.exitValue();
        // "diff --quiet" answers 1 for "there are changes"; anything else non-zero is a failure.
        if (exit != 0 && !(cmd.length > 2 && cmd[1].equals("diff"))) {
            throw new IOException("git " + cmd[1] + " failed (" + exit + ")");
        }
        return new Result(exit, out);
    }
}
