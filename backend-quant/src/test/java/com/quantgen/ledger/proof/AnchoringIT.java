package com.quantgen.ledger.proof;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.quantgen.support.IntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** F-POB-6: each published root is committed to the anchor repository once, and the public roots show the commit. */
@TestPropertySource(properties = "qg.proofs.anchor.repo-url=https://github.com/example/quantgen-proofs")
class AnchoringIT extends IntegrationTest {

    @Autowired
    BalanceSnapshotService snapshots;

    @Autowired
    LedgerSealer sealer;

    @Autowired
    JsonMapper json;

    @Autowired
    Clock clock;

    @TempDir
    Path repo;

    @Test
    void aRootIsCommittedOnceAndPublishedWithItsCommit() throws Exception {
        git("init", "-q");
        sealer.sealPending(true);
        LocalDate date = LocalDate.of(2095, 6, 1);
        snapshots.take(date);
        RootAnchorer anchorer = new RootAnchorer(jdbc, snapshots, json, clock, repo.toString(), "git");

        assertThat(anchorer.anchorPending()).isGreaterThanOrEqualTo(1);
        JsonNode file = json.readTree(Files.readString(repo.resolve("roots/" + date + ".json")));
        JsonNode published = get("/api/v1/public/proofs/roots").jsonBody();
        JsonNode mine = null;
        for (JsonNode r : published) {
            if (r.get("date").asString().equals(date.toString())) {
                mine = r;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(file.get("root").asString()).isEqualTo(mine.get("root").asString());
        assertThat(file.get("totalLiabilities").asString()).isEqualTo(mine.get("totalLiabilities").asString());
        String sha = git("log", "-n", "1", "--format=%H", "--", "roots/" + date + ".json").strip();
        assertThat(mine.get("anchor").get("commit").asString()).isEqualTo(sha);
        assertThat(mine.get("anchor").get("repo").asString()).isEqualTo("https://github.com/example/quantgen-proofs");
        assertThat(mine.get("anchor").get("committedAt").isNull()).isFalse();

        // Already anchored: nothing new is committed.
        String head = git("rev-parse", "HEAD").strip();
        assertThat(anchorer.anchorPending()).isZero();
        assertThat(git("rev-parse", "HEAD").strip()).isEqualTo(head);
        assertThat(git("status", "--porcelain").strip()).isEmpty(); // nothing left uncommitted, never pushed
    }

    @Test
    void aDirectoryThatIsntAGitCloneIsRefused() {
        RootAnchorer anchorer = new RootAnchorer(jdbc, snapshots, json, clock, repo.toString(), "git");
        org.assertj.core.api.Assertions.assertThatThrownBy(anchorer::anchorPending)
                .hasMessageContaining("not a git clone");
    }

    private String git(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.exitValue()).as(out).isZero();
        return out;
    }
}
