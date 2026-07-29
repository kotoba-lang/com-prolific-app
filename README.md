# com-prolific-app

Prolific (`prolific.com`) integration for the fleet: recruit identity-verified
human participants through an API instead of a vendor UI, so a study is a
reproducible command rather than a form somebody filled in once.

Unlike the clean-room `com-*` actors in this org, this is **not** a
reimplementation of Prolific's API — it is a client for the real one. Prolific
sells access to a pool of real people; there is nothing to clean-room.

## Why it exists here

Extracted from `network-awai/cloud-itonami`'s `scripts/prolific-study.cljs`,
which was the first consumer. The same argument as the nexus-x402 extraction:
the reward arithmetic, the audience filter resolution and the submission
triage are not cloud-itonami concerns, and a second product wanting user
testing should not fork a copy of the money code.

## One source, not the panel

cloud-itonami's ADR-0035 decided the platform runs **its own** two-sided
research panel (ISIC 7320 demand / ISCO 4227 supply) rather than buying a
vendor's. This client is therefore **one recruitment source that can feed
that panel**, never the panel itself.

That is why the panel's join key is `{{PARTICIPANT_REF}}` and not
`{{%PROLIFIC_PID%}}` — invariant 2 in that ADR names it in the platform's own
vocabulary precisely to keep studies portable off any one vendor. A caller
wiring this client into a study is responsible for mapping Prolific's
participant id onto a panel ref; this library deliberately does not assume it
owns that identity.

## Layout

```
src/prolific/core.cljc     pure: rewards, filters, study payload, triage
src/prolific/client.cljs   HTTP: fetch/post against api.prolific.com
test/prolific/core_test.cljc
```

The split is the point. Everything that decides **what a participant is paid**,
**which population is recruited** and **which submissions get money** is pure
and runs on both hosts with no network and no token. `client.cljs` only moves
bytes.

## Money is integer cents

`reward-cents` and `cost-breakdown` never touch floating point beyond one
conversion of the configured hourly rate.

This is load-bearing, not stylistic. The first version computed
`(* 100 hourly (/ minutes 60))`; the JVM evaluated that to `200.00000000000006`
where JS got exactly `200`, so `Math/ceil` crossed to the next 5c bracket on
one host and not the other — **the same study paid $2.05 under `clojure` and
$2.00 under `nbb`**. The test suite pins the values both hosts must produce.

## Rates

- `default-hourly-usd` is **12.00**, Prolific's recommended rate, not the 8.00
  floor. A study whose entire output is written answers is the wrong place to
  save 30%: underpaying buys rushed participants and unusable free text.
- `default-fee-rate` is **0.428**, the corporate platform fee (academic and
  non-profit is 0.333).

## Audience filters

Choice ids are account-scoped and opaque (`"0"`, `"13"`, …), so
`resolve-languages` reads them from a live `GET /filters/` response rather
than hardcoding. It prefers the *fluent languages* filter over *first
language*: what decides whether someone can do the task is whether they can
read the screen, not what they grew up speaking.

An unmatched request is returned as `{:error :unmatched :missing [...]}` and
never silently dropped — dropping one would recruit a population nobody asked
for.

## Paying people

`triage` separates submissions awaiting review into those that carry a
completion code and those that do not. The second group are the participants
who abandoned the task: an auto-approval rule keyed on the completion code
cannot reach them, and they are owed payment all the same. They are also the
people a usability study most needs to hear from.

```clojure
(require '[prolific.core :as p] '[prolific.client :as c])

(p/usd (:total-cents (p/cost-breakdown 30 10)))   ;=> "$85.68"

(-> (c/resolve-languages ["English" "Hindi" "Chinese" "Spanish" "Arabic"])
    (.then #(c/create-study (p/study-payload task {:places 5 :language-filter %
                                                   :completion-code "…"
                                                   :external-url "…"}))))

(c/approve-all study-id)   ;=> {:total 30 :approved 4 :failed []}
```

## Endpoints, as documented

`/submissions/?study={id}` — **not** `/studies/{id}/submissions/`, which does
not exist and which the first draft of the caller used.

## Test

```sh
npm test          # nbb / JS host
clojure -M:test   # JVM host — must agree exactly
```

## Status

Scaffolded 2026-07-28. **The client has never been run against the live API** —
every test here uses fixtures shaped like the documented responses. Before
trusting it with money, confirm the real filter ids with
`GET /filters/` and compare against `filters-fixture` in the test.
