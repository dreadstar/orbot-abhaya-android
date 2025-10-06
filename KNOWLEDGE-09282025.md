KNOWLEDGE — Decentralized signature / capability token / verification design
Date: 2025-09-28

This document describes a complete, decentralized signature, capability-token, and verification design for the Orbot/Abhaya mesh project. It is written to match the project's constraints: highly anonymous mesh, no central controller, Ed25519 signing, and the existing MMCP/gossip transport. The design focuses on negotiation flows (ResourceRequest/ResourceOffer), distributed storage and compute, revocation and delegation, and how to wire the solution into the codebase.

Goals & constraints

- Goals
  - Provide a lightweight, decentralized authorization system suitable for negotiation and capability delegation across a highly anonymous mesh.
  - Ensure payloads can be verified efficiently on mobile devices.
  - Support short-lived delegation via ephemeral keys for privacy.
  - Require no centralized PKI, registry, or datastore.

- Constraints
  - Use Ed25519 (JCA Signature.getInstance("Ed25519")).
  - Public keys in tokens are Base64 of X.509 encoded public key bytes (consistent with existing code).
  - Canonicalization rules: remove "signature" and "signerPublicKey" fields, lexicographically sort keys (deep), serialize deterministically, encode as UTF-8, then verify signature over bytes.
  - All messaging occurs over MMCP/gossip; no AIDL/IPC for negotiation.

High-level design

1) Core ideas
- Devices have long-term identity keys (Ed25519). These are used to issue short-lived delegation tokens to ephemeral keys.
- Capability tokens (signed JSON) declare a scope (what action is permitted) and a short expiry.
- The actual mesh payloads are signed by ephemeral keys (delegated credentials). Verification requires checking both the bearer signature and the delegation chain up to a trusted key (TOFU or web-of-trust).
- Revocation is handled via short TTLs for tokens + optional gossipable revocation announcements. Replay protection uses nonces + tokenId caching.

2) Token types
- Capability token: grant to perform specific action (upload blob, make offer, accept assignment). Small JSON object with scope, expiry, nonce, tokenId, issuer.
- Delegation token: long-term key signs a token that delegates privileges to an ephemeral public key.
- Receipt token: post-action signed receipt used to build reputation.
- Revocation announcement: signed key revocation metadata gossiped across the mesh.

Token schema (canonical JSON fields)

- Capability token (example):
{
  "tokenId": "<uuid>",
  "issuer": "<Base64(X509 public key)>",
  "subject": "<Base64(X509 public key)>|\"any\"",
  "issuedAt": 1690000000000,
  "expiresAt": 1690000000000,
  "scope": { "kind":"resource_offer", "resourceId":"blob-<hash>", "maxBytes":10485760 },
  "constraints": { /* optional */ },
  "nonce": "<random-hex>",
  // signature fields MUST be removed prior to canonicalization
  "signerPublicKey": "<Base64(X509)>",
  "signature": "<Base64(sig)>"
}

Rules:
- The JSON above is canonicalized by removing "signerPublicKey" and "signature", deep-sorting object keys, and converting to UTF-8 bytes.
- The signature is an Ed25519 signature over the canonicalized bytes.
- The tokenId is unique per issuance; verifier keeps a short sliding-window cache to mitigate replay.

Delegation (ephemeral keys)

- Devices normally use ephemeral keys (E) to sign offers/responses. Ephemeral keys are short-lived.
- The device's long-term key (L) issues a delegation token to E describing allowed scopes and the expiry. Example delegation token is a capability token with subject==Epub.
- The message flow: Offer signed by E + inclusion of E's delegation token signed by L (either embedded in payload or discoverable via gossip).
- Verifier checks E's signature over the offer, then verifies the delegation token (signature by L) and checks trust for L via TOFU/endorsements.

Trust model (fully decentralized)

- Trust-on-first-use (TOFU): When a new long-term key is first observed, store it locally with minimal trust; collect receipts to increase trust.
- Web-of-Trust: peers can create endorsements (signed attestations) of other keys. Nodes use a depth-limited trust expansion to boost trust of keys.
- Receipts & reputation: When interactions succeed (e.g., uploads accepted & verified), participants create signed receipts that are gossiped and increase reputational weight for keys.
- Local policy: Each node has configurable thresholds for operations that require higher trust (e.g., accepting storage offers requires >=N reputation points).

Revocation & rotation

- Prefer short-lived tokens to minimize need for revocation. For long-term key compromise, owner can publish a signed revocation announcement (if still controlling key).
- Gossip revocation announcements with freshness and TTL. Store a local revoked-keys set for enforcement.
- Key rotation: Old key signs an attestation for the new key (rotation attestation). Without control of old key, recovery uses social/web-of-trust endorsements.

Replay protection

- Token includes nonce + tokenId; verifiers cache tokenId for a short window.
- For critical operations, a challenge-response should be used (verifier sends random challenge; requester signs it with subject key to prove possession).

Privacy & unlinkability

- Use ephemeral keys for offers and limit use of long-term keys to delegation issuance.
- Avoid embedding persistent identifiers in tokens.

Sybil & spam mitigations

- Rate limit per observed virtual node address or per token issuer.
- Reputation receipts and endorsement networks increase cost to abuse.
- Optional lightweight PoW or stake/collateral for initial trust; not required but available as a configurable mitigation.

Canonicalization & verification algorithm (concrete)

1) Parse the JSON payload.
2) Extract and remove signature fields: "signature" and "signerPublicKey". Also remove any transport-level fields that should not be included in canonicalization.
3) Canonicalize the remaining JSON strictly: deep lexicographical sort of keys, deterministic serialization without whitespace, UTF-8 encoding.
4) Decode signerPublicKey (Base64 -> X509 bytes -> KeyFactory("Ed25519").generatePublic(X509EncodedKeySpec(bytes))).
5) Use Signature.getInstance("Ed25519").initVerify(publicKey) and feed canonicalized bytes, then verify(signatureBytes).
6) If signature passes, check token-specific fields (expiry, scope, tokenId replay, subject binding). If subject != "any", ensure that the message is signed by subject key or the subject key has a valid delegation token chain.

Integration with the codebase

- MmcpMessageFactory.createDelegationMessage: continue to use it to attach message-level signatures (signerPublicKey/signature fields). Provide the signed JSON as the delegation payload.
- VirtualNodeToSignedJsonFlowAdapter and GossipTransport: forward verbatim JSON payloads; do not reserialize or mutate payloads.
- ResourceOfferVerifier: extend to parse capability/delegation tokens and verify delegation chains; return a verification result structure containing issuer/subject pubkeys and token scope.
- MeshrabiyaClient: call the verifier and apply local acceptance policy (trust threshold, matching scope, expiry).

Testing

- Unit tests
  - Canonicalization + signature verification (happy and tampered cases).
  - Delegation chain validation: ephemeral key delegated by long-term key.
  - Replay prevention (same token used twice within cache window).
  - Expiry rejection.

- Integration tests
  - End-to-end: ephemeral delegation issuance, send signed offer over adapter, sensor verifies and accepts.
  - Tamper tests: alter payload after signing and ensure verification fails.

Implementation checklist (practical next steps)

- [ ] Extend `ResourceOfferVerifier` to recognize capability/delegation token fields and verify delegation chains.
- [ ] Implement `TrustStore` (TOFU + receipts + revoked keys cache + small reputation scoring).
- [ ] Token creation helpers: `createCapabilityToken`, `createDelegationToken`, `createReceipt`.
- [ ] Integrate adapter wiring (DI + optional runtime feature flag) in `DelegationOrchestrator` / `ReplicationWorker`.
- [ ] Tests: unit + integration as listed above.
- [ ] Instrumentation: add logs/metrics for verification failures and token usage.

Security considerations (summary)

- Replay: prevented via nonces/tokenIds + short TTL + challenge-response for critical ops.
- Tamper: canonicalization + signature protects payload.
- Compromise: short-lived tokens reduce impact; revocation announcements help when owners can still sign.
- Sybil: rate-limits, receipts, endorsements, and optional PoW mitigate large-scale abuse.

Appendix — example verification pseudocode (Kotlin-like)

fun verifySignedJson(payloadJson: String): VerificationResult {
  val jsonObj = JSONObject(payloadJson)
  val signatureB64 = jsonObj.optString("signature", null)
  val signerPubB64 = jsonObj.optString("signerPublicKey", null)
  if(signatureB64 == null || signerPubB64 == null) return VerificationResult.invalid("missing signature/pubkey")

  // remove signature fields
  jsonObj.remove("signature")
  jsonObj.remove("signerPublicKey")

  val canonicalBytes = canonicalizeJson(jsonObj)
  val signerPubBytes = Base64.getDecoder().decode(signerPubB64)
  val keySpec = X509EncodedKeySpec(signerPubBytes)
  val keyFactory = KeyFactory.getInstance("Ed25519")
  val pubKey = keyFactory.generatePublic(keySpec)

  val verifier = Signature.getInstance("Ed25519")
  verifier.initVerify(pubKey)
  verifier.update(canonicalBytes)
  val ok = verifier.verify(Base64.getDecoder().decode(signatureB64))
  if(!ok) return VerificationResult.invalid("signature mismatch")

  // parse token fields like expiresAt, tokenId, scope and apply checks
  // ...
  return VerificationResult.valid(...)
}

Notes & rationale

- Using Ed25519 keeps signatures small and fast.
- The canonicalization approach matches the project's existing ResourceOfferVerifier and ensures determinism between signer and verifier.
- Delegation reduces linkability by using ephemeral keys.
- Decentralized trust is pragmatic: TOFU + receipts + endorsements provide workable trust without central authorities.

References & further work

- Implementation of the extended `ResourceOfferVerifier` with token parsing/chain verification.
- Small `TrustStore` to persist key observations and receipts.
- Documentation for operator-configurable trust thresholds.


---
Generated by the development assistant on 2025-09-28
