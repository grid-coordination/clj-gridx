# Contributing to clj-gridx

Thanks for your interest in contributing! This repo is a Clojure client library for the [GridX Pricing API](https://pe-api.gridx.com), providing access to marginal cost pricing data for California utilities (PG&E and SCE). It is built on a [non-official OpenAPI spec](https://github.com/grid-coordination/gridx-api-specs#disclaimer) derived from GridX's public developer docs, and exposes a two-layer raw/coerced data model with malli schemas and tick intervals.

## How to contribute

### Discussions

Use [Discussions](https://github.com/grid-coordination/clj-gridx/discussions) for:

- Questions about how to use the library — clients, pricing fetches, coercion, schemas, time handling
- API and design judgment calls — "should clj-gridx model X?" / "is this the right shape for Y?"
- GridX API behavior gaps that affect clj-gridx — when the API exposes something that doesn't fit the current entity shape and you want to scope what the library should do about it
- Coordination with the upstream [gridx-api-specs](https://github.com/grid-coordination/gridx-api-specs) repo (the OpenAPI specs this client consumes)
- Sharing what you're building on top of clj-gridx

Discussions are open-ended — a good place to think out loud or scope something before it becomes a concrete change. Aligned outcomes from a Discussion often turn into one or more Issues.

### Issues

Use [Issues](https://github.com/grid-coordination/clj-gridx/issues) for actionable changes:

- Bugs in client construction, request building, response parsing, or coercion against the live API
- Coercion or schema gaps surfaced by real API responses (a field the library doesn't handle, or a value that breaks the coerced shape)
- New utility support, new endpoints, or new request parameters when GridX exposes them
- Test failures or unexpected behavior with concrete repro steps
- Documentation errors, unclear explanations, or stale prose in `README.md` or namespace docstrings
- Discussion outcomes that have alignment and a clear scope

If you're not sure whether something is an Issue or a Discussion, start with a Discussion — we can convert it later.

### Pull requests

Pull requests are welcome.

- For small fixes (typos, broken links, single-test corrections, single-coercion bug fixes), open a PR directly.
- For substantive changes (new utility support, new endpoints, new schema fields, new coercion behavior, new namespaces), open a Discussion or Issue first so we can align on scope before you invest the effort.
- All changes pass `clojure -M:test` (Kaocha) and `clj-kondo --lint src test` cleanly.
- Match the existing tone and structure. The library composes spec-driven HTTP → raw response → coerced entities as roughly orthogonal layers; patches that fit cleanly into one layer without leaking concerns across them are the easiest to land.
- One commit per logical change is fine; we don't require squash or any particular branch naming.

## Development

```bash
clojure -M:test                 # run the Kaocha unit test suite (offline, sample JSON)
clojure -M:test-integration     # integration tests against the live stage API
clojure -M:nrepl                # nREPL on the port written to .nrepl-port
clj-kondo --lint src test       # lint
```

The OpenAPI specs under `resources/gridx-pricing-spec/` are vendored from the [gridx-api-specs](https://github.com/grid-coordination/gridx-api-specs) repo and serve as the single source of truth for routes, parameters, and request/response shapes — see [`resources/gridx-pricing-spec/ORIGIN.md`](resources/gridx-pricing-spec/ORIGIN.md) for provenance. When the upstream specs change, re-vendor the relevant files and re-run the integration tests to confirm the wire format still matches.

## Code of conduct

Be respectful and constructive. We're a small project and appreciate everyone who takes the time to file an issue or send a PR.

## Important notice

This library is provided on an "as-is" basis. Updates and maintenance, including responses to issues filed on GitHub, will take place on an "as time and resources permit" basis. Library output (raw API responses, coerced curves/intervals/components) is best-effort against the GridX Pricing API as documented in the [gridx-api-specs](https://github.com/grid-coordination/gridx-api-specs) repo, which is itself a non-official OpenAPI spec derived from GridX's public developer docs. This library is not authoritative for billing — independent verification against the source utility tariff and GridX API responses is recommended for any consumer using these results for billing-correctness purposes.
