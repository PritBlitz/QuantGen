# k6 load tests (PRD F-OPS-4)

Free and local: they run from the `grafana/k6` Docker image, so there's nothing to install. Each script
registers throwaway customers in `setup()` and **ends by checking that the ledger reconciles**
(`GET /admin/ledger/reconcile`). That check is the pass condition from PRD §11.6.

The backend must run with `QG_RATE_LIMIT_ENABLED=false`, because setup registers many users from one IP.
Never point these scripts at a deployment with real customers.

```bash
# from the repo root, backend on :8080
docker run --rm -i -v "$PWD/infra/k6:/k6" grafana/k6:1.0.0 run /k6/transfers.js
docker run --rm -i -v "$PWD/infra/k6:/k6" grafana/k6:1.0.0 run /k6/orders.js
# tune: -e QG_BASE_URL=http://host.docker.internal:8080 -e USERS=20 -e VUS=10 -e DURATION=60s
```

| Script | What it does | Thresholds |
|---|---|---|
| `transfers.js` | A ring of customers paying each other, plus every 4th transfer going to one hot account. About 10% of iterations replay the same `Idempotency-Key`. | checks > 99%, transfer p95 < 800 ms, < 1% failed, **0 duplicate transfers**, ledger reconciles |
| `orders.js` | 1-share market buys, each followed by a sell, while the simulator ticks every 3 s | checks > 99%, order p95 < 800 ms, ledger reconciles |

Results on 2026-10-24 (dev laptop, backend `5ac903a`):
- `transfers.js` (6 VUs, 20 s): 770 transfers, 0 failures, 73 replays all returned the original transfer, p95 274 ms. Ledger balanced.
- `orders.js` (5 VUs, 15 s), backend `5ac903a`: about 4% of orders got `409 INVALID_STATE` when they landed
  during a price tick. **Fixed in backend `8d59c77`:** 342/342 filled in 20 s, ledger balanced. p95 was 778 ms, close to the
  800 ms threshold on a busy laptop; recheck it on a quiet machine.
