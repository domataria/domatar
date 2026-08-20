/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;
import com.domatar.util.DomatarException;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Phase 1 crypto primitives.
 * All tests run with no live database, no Tomcat, no config file.
 */
class CryptoTest
{
    // -----------------------------------------------------------------------
    // AccountKeys
    // -----------------------------------------------------------------------

    @Test
    void accountKeys_generate_actIdIs32Chars()
    {
        final AccountKeys keys = AccountKeys.generate();
        assertEquals(32, keys.actId.length(),
            "actId must be exactly 32 characters");
    }

    @Test
    void accountKeys_generate_actIdContainsNoDotOrAt()
    {
        final AccountKeys keys = AccountKeys.generate();
        assertFalse(keys.actId.contains("."), "actId must not contain '.'");
        assertFalse(keys.actId.contains("@"), "actId must not contain '@'");
    }

    @Test
    void accountKeys_generate_actIdIsDeterministic()
    {
        // The same public key always produces the same actId.
        final AccountKeys keys = AccountKeys.generate();
        final String derived = AccountKeys.deriveActId(keys.rootPubKey);
        assertEquals(keys.actId, derived,
            "deriveActId(rootPubKey) must equal the generated actId");
    }

    @Test
    void accountKeys_generate_twoCallsDiffer()
    {
        final AccountKeys k1 = AccountKeys.generate();
        final AccountKeys k2 = AccountKeys.generate();
        assertNotEquals(k1.actId, k2.actId,
            "Two independently generated actIds must differ");
    }

    @Test
    void accountKeys_fromPrivKey_roundTrips()
    {
        final AccountKeys original      = AccountKeys.generate();
        final AccountKeys reconstructed = AccountKeys.fromPrivKey(original.rootPrivKey);

        assertEquals(original.actId, reconstructed.actId,
            "fromPrivKey must reproduce the same actId");
        assertArrayEquals(original.rootPubKey, reconstructed.rootPubKey,
            "fromPrivKey must reproduce the same public key");
    }

    @Test
    void accountKeys_deriveId_v1MatchesDeriveActId() throws Exception
    {
        final AccountKeys keys = AccountKeys.generate();
        assertEquals(AccountKeys.deriveActId(keys.rootPubKey),
                     AccountKeys.deriveId(keys.rootPubKey, 1));
    }

    @Test
    void accountKeys_fingerprintsTo_v1()
    {
        final AccountKeys keys  = AccountKeys.generate();
        final AccountKeys other = AccountKeys.generate();

        assertTrue(AccountKeys.fingerprintsTo(keys.rootPubKey, keys.actId, 1));
        assertFalse(AccountKeys.fingerprintsTo(keys.rootPubKey, other.actId, 1));
    }

    @Test
    void accountKeys_deriveId_unsupportedVersionThrows()
    {
        final AccountKeys keys = AccountKeys.generate();
        final DomatarException ex = assertThrows(DomatarException.class,
            () -> AccountKeys.deriveId(keys.rootPubKey, 2));
        assertEquals("Unsupported FpVersion 2", ex.getMessage());
    }

    @Test
    void accountKeys_defaultAndRegisteredVersions()
    {
        assertEquals(1, AccountKeys.defaultVersion());
        assertArrayEquals(new int[] { 1 }, AccountKeys.registeredVersions());
    }

    @Test
    void accountKeys_fromPrivKey_withVersion1()
    {
        final AccountKeys original      = AccountKeys.generate();
        final AccountKeys reconstructed = AccountKeys.fromPrivKey(original.rootPrivKey, 1);
        assertEquals(original.actId, reconstructed.actId);
    }

    // -----------------------------------------------------------------------
    // KeyOps
    // -----------------------------------------------------------------------

    @Test
    void keyOps_signVerify_roundTrip()
    {
        final KeyPair kp      = KeyOps.generateKeyPair();
        final byte[]  message = "hello Domatar".getBytes();
        final byte[]  sig     = KeyOps.sign(kp.getPrivate(), message);

        assertEquals(64, sig.length, "Ed25519 signature must be 64 bytes");
        assertTrue(KeyOps.verify(kp.getPublic(), message, sig),
            "Signature must verify with the matching public key");
    }

    @Test
    void keyOps_verify_failsOnTamperedMessage()
    {
        final KeyPair kp      = KeyOps.generateKeyPair();
        final byte[]  message = "original".getBytes();
        final byte[]  sig     = KeyOps.sign(kp.getPrivate(), message);
        final byte[]  tampered = "tampered".getBytes();

        assertFalse(KeyOps.verify(kp.getPublic(), tampered, sig),
            "Signature must not verify against a tampered message");
    }

    @Test
    void keyOps_publicKeyBytes_roundTrip()
    {
        final KeyPair kp       = KeyOps.generateKeyPair();
        final byte[]  rawBytes = KeyOps.publicKeyBytes(kp.getPublic());
        assertEquals(32, rawBytes.length, "Ed25519 public key must be 32 bytes");

        final byte[] roundTripped = KeyOps.publicKeyBytes(
                KeyOps.publicKeyFromBytes(rawBytes));
        assertArrayEquals(rawBytes, roundTripped, "Public key bytes must round-trip");
    }

    @Test
    void keyOps_privateKeyBytes_roundTrip()
    {
        final KeyPair kp       = KeyOps.generateKeyPair();
        final byte[]  rawBytes = KeyOps.privateKeyBytes(kp.getPrivate());
        assertEquals(32, rawBytes.length, "Ed25519 private key seed must be 32 bytes");

        final byte[] roundTripped = KeyOps.privateKeyBytes(
                KeyOps.privateKeyFromBytes(rawBytes));
        assertArrayEquals(rawBytes, roundTripped, "Private key bytes must round-trip");
    }

    @Test
    void keyOps_sha256_producesCorrectLength()
    {
        final byte[] hash = KeyOps.sha256("test".getBytes());
        assertEquals(32, hash.length, "SHA-256 must produce 32 bytes");
    }

    // -----------------------------------------------------------------------
    // CanonicalJson
    // -----------------------------------------------------------------------

    @Test
    void canonicalJson_keySortingIsDeterministic()
    {
        final JsonHashMap m1 = new JsonHashMap();
        m1.put("z", "last");
        m1.put("a", "first");
        m1.put("m", "middle");

        final JsonHashMap m2 = new JsonHashMap();
        m2.put("m", "middle");
        m2.put("z", "last");
        m2.put("a", "first");

        assertArrayEquals(
            CanonicalJson.canonicalize(m1),
            CanonicalJson.canonicalize(m2),
            "Maps with same entries in different insertion order must produce identical bytes");
    }

    @Test
    void canonicalJson_excludingRemovesField()
    {
        final JsonHashMap map = new JsonHashMap();
        map.put("ActId", "abc");
        map.put("OriginSig", "signature_to_exclude");
        map.put("Nonce", "xyz");

        final byte[] withSig    = CanonicalJson.canonicalize(map);
        final byte[] withoutSig = CanonicalJson.canonicalizeExcluding(map, "OriginSig");

        // withoutSig must be shorter and not contain the OriginSig value
        assertTrue(withoutSig.length < withSig.length,
            "canonicalizeExcluding must produce fewer bytes than the full form");

        final String withoutStr = new String(withoutSig, java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(withoutStr.contains("OriginSig"),
            "The excluded field key must not appear in the output");
        assertFalse(withoutStr.contains("signature_to_exclude"),
            "The excluded field value must not appear in the output");
    }

    @Test
    void canonicalJson_noWhitespace()
    {
        final JsonHashMap map = new JsonHashMap();
        map.put("key", "value");

        final String result = new String(CanonicalJson.canonicalize(map),
            java.nio.charset.StandardCharsets.UTF_8);

        assertFalse(result.contains(" "),  "Canonical JSON must have no spaces");
        assertFalse(result.contains("\n"), "Canonical JSON must have no newlines");
    }

    @Test
    void canonicalJson_parseStringAndRoundTrip()
    {
        final String json = "{\"b\":2,\"a\":1}";
        final byte[] bytes = CanonicalJson.canonicalize(json);
        final String result = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);

        // Keys must be sorted: a before b
        assertTrue(result.indexOf("\"a\"") < result.indexOf("\"b\""),
            "Canonical JSON must sort keys: 'a' before 'b'");
    }

    // -----------------------------------------------------------------------
    // MasterKey
    // -----------------------------------------------------------------------

    @Test
    void masterKey_sealOpenRoundTrip_withoutMasterKey()
    {
        // No DOMATAR_MASTER_KEY set in test environment → raw pass-through.
        final byte[] plaintext = "secret-32-byte-private-key-seed!".getBytes();
        final String sealed    = MasterKey.seal(plaintext);

        assertTrue(sealed.startsWith("raw:"),
            "Without MasterKey config, sealed value must start with 'raw:'");

        final byte[] opened = MasterKey.open(sealed);
        assertArrayEquals(plaintext, opened, "seal/open must round-trip the plaintext");
    }

    // -----------------------------------------------------------------------
    // Delegation + Binding (OwnIds Phase 1)
    // -----------------------------------------------------------------------

    @Test
    void accountKeys_deriveOwnId_matchesDeriveActId()
    {
        final AccountKeys keys = AccountKeys.generate();
        assertEquals(AccountKeys.deriveActId(keys.rootPubKey),
                     AccountKeys.deriveOwnId(keys.rootPubKey));
    }

    @Test
    void delegation_issue_verifyAgainstBinding()
    {
        final AccountKeys genesis = AccountKeys.generate();
        final AccountKeys own     = AccountKeys.generate();
        final Binding     binding = Binding.sign(genesis, own.rootPubKey,
                                                 System.currentTimeMillis());

        final long notAfter = System.currentTimeMillis() + 3_600_000L;
        final Delegation deleg = Delegation.issue(genesis.actId, own, "prv1", notAfter);

        assertTrue(deleg.verify(binding),
            "Ownership-signed delegation must verify against matching binding");
        assertEquals(genesis.actId, deleg.actId);
        assertNotEquals(own.actId, deleg.actId,
            "Delegation.actId must be the account actId, not the ownId");
    }

    @Test
    void delegation_verify_failsWhenOwnPubKeyMismatchesBinding()
    {
        final AccountKeys genesis = AccountKeys.generate();
        final AccountKeys own     = AccountKeys.generate();
        final AccountKeys other   = AccountKeys.generate();
        final Binding     binding = Binding.sign(genesis, own.rootPubKey, 1L);

        final Delegation wrong = Delegation.issue(genesis.actId, other, "prv1",
            System.currentTimeMillis() + 3_600_000L);

        assertFalse(wrong.verify(binding),
            "Delegation whose OwnPubKey does not match binding.ownId must fail");
    }
}
