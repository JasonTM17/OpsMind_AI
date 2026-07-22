# OpsMind JSON Schemas

This is the sole public and model-output JSON Schema root. Schemas are
versioned by directory and referenced by the OpenAPI document; each service
validates at its ingress boundary instead of declaring a competing shape.

Phase 3 provides common Problem Details plus principal, tenant-scope, and
delegated-capability contracts. Phase 5 adds provider-neutral structured model
output under `ai-runtime/v1/`; the runtime validates the same contract with
Pydantic before accepting any provider response. A service-local copy is
prohibited.
