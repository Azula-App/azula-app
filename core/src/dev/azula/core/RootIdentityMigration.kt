package dev.azula.core

// multi-device-identity design.md Migration Plan step 2 / the `identity` delta's
// "Identity Is a Root Keypair Above Device Endpoint Keys" requirement: an existing (or
// freshly minted) single-device identity's endpoint secret becomes its root secret
// unchanged, and the device keeps using that same key as its device key too --
// a self-certificate where `device_pk == root_pk`.
//
// This is pure computation only: it neither decides *whether* a device needs to adopt a
// root secret (that's a storage question -- "is there already a root entry?" -- for the
// platform's key store) nor persists anything itself. `network-real` is the caller that
// knows the per-platform `SecretKeyStore` seam and has a real [Ed25519] wired to iroh-kmp;
// this function just needs the two keys and a clock.

/**
 * Self-issues a `device_pk == root_pk` certificate for a device adopting [rootSecret]
 * (its own former, or freshly minted, endpoint secret) as the identity's root secret. [devicePk]
 * is that same key's already-known public key -- the transport's endpoint id bytes, or whatever
 * iroh-kmp's `ed25519PublicFromSecret` derives from [rootSecret] -- they are the same 32
 * bytes here by construction, since the device adopting root authority *is* the endpoint key.
 *
 * The recovery phrase, endpoint id, and existing contacts are unaffected by this call: it
 * neither touches a endpoint-key entry nor rebinds any transport -- see
 * [`Identity Is a Root Keypair Above Device Endpoint Keys`](azula-docs/openspec/changes/multi-device-identity/specs/identity/spec.md).
 *
 * This function still deliberately does NOT persist the resulting certificate anywhere
 * durable or append a `device_add` log entry for it -- `core` has no event-log dependency,
 * and neither does `network-real` (its caller). That's done instead by
 * `AccountSyncService.ensureSelfCertified` in `shared` (multi-device-identity task 3.4,
 * closing this file's former TODO), which recomputes the identical certificate from the
 * same inputs and durably logs it. Safe to also keep recomputing it here idempotently --
 * same inputs, same deterministic Ed25519 signature.
 */
object RootIdentityMigration {
    fun selfCertify(
        rootSecret: ByteArray,
        devicePk: ByteArray,
        nowEpochSeconds: Long,
        ed25519: Ed25519,
    ): DeviceCert {
        require(rootSecret.size == PUBLIC_KEY_LEN) { "rootSecret must be $PUBLIC_KEY_LEN bytes" }
        require(devicePk.size == PUBLIC_KEY_LEN) { "devicePk must be $PUBLIC_KEY_LEN bytes" }
        return DeviceCertCodec.issue(
            roles = 0,
            rootPk = devicePk,
            devicePk = devicePk,
            issuedAt = nowEpochSeconds,
            expiresAt = 0,
            name = "",
            rootSecret = rootSecret,
            ed25519 = ed25519,
        )
    }
}
