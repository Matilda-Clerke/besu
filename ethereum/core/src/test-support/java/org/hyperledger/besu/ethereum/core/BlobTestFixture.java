/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.core;

import org.hyperledger.besu.datatypes.VersionedHash;
import org.hyperledger.besu.ethereum.core.kzg.Blob;
import org.hyperledger.besu.ethereum.core.kzg.BlobsWithCommitments;
import org.hyperledger.besu.ethereum.core.kzg.KZGCommitment;
import org.hyperledger.besu.ethereum.core.kzg.KZGProof;

import java.util.ArrayList;
import java.util.List;

import ethereum.ckzg4844.CKZG4844JNI;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.Bytes48;
import org.bouncycastle.crypto.digests.SHA256Digest;

/**
 * A utility class for creating blobs and their associated cryptographic artifacts (KZG commitments,
 * proofs, and versioned hashes) for testing purposes.
 *
 * <p>This class facilitates testing scenarios involving Ethereum's blob and KZG cryptographic
 * primitives. It uses the CKZG4844JNI library to compute KZG commitments and proofs and provides
 * methods to generate blobs and their associated data.
 *
 * <p>To ensure the required trusted setup is loaded, use the {@link
 * org.hyperledger.besu.ethereum.util.TrustedSetupClassLoaderExtension} in your test classes. This
 * extension handles loading the native library and trusted setup automatically.
 *
 * @see org.hyperledger.besu.ethereum.util.TrustedSetupClassLoaderExtension
 */
public class BlobTestFixture {
  private byte byteValue = 0x00;

  record BlobTriplet(
      Blob blob, KZGCommitment kzgCommitment, KZGProof kzgProof, VersionedHash versionedHash) {}

  public BlobTriplet createBlobTriplet() {
    byte[] rawMaterial = new byte[131072];
    rawMaterial[0] = byteValue++;

    Bytes48 commitment = Bytes48.wrap(CKZG4844JNI.blobToKzgCommitment(rawMaterial));

    Bytes48 proof =
        Bytes48.wrap(CKZG4844JNI.computeBlobKzgProof(rawMaterial, commitment.toArray()));
    VersionedHash versionedHash = hashCommitment(new KZGCommitment(commitment));
    return new BlobTriplet(
        new Blob(Bytes.wrap(rawMaterial)),
        new KZGCommitment(commitment),
        new KZGProof(proof),
        versionedHash);
  }

  public BlobsWithCommitments createBlobsWithCommitments(final int blobCount) {
    List<Blob> blobs = new ArrayList<>();
    List<KZGCommitment> commitments = new ArrayList<>();
    List<KZGProof> proofs = new ArrayList<>();
    List<VersionedHash> versionedHashes = new ArrayList<>();
    for (int i = 0; i < blobCount; i++) {
      BlobTriplet blobTriplet = createBlobTriplet();
      blobs.add(blobTriplet.blob());
      commitments.add(blobTriplet.kzgCommitment());
      proofs.add(blobTriplet.kzgProof());
      versionedHashes.add(blobTriplet.versionedHash());
    }
    return new BlobsWithCommitments(commitments, blobs, proofs, versionedHashes);
  }

  private VersionedHash hashCommitment(final KZGCommitment commitment) {
    final SHA256Digest digest = new SHA256Digest();
    digest.update(commitment.getData().toArrayUnsafe(), 0, commitment.getData().size());

    final byte[] dig = new byte[digest.getDigestSize()];

    digest.doFinal(dig, 0);

    dig[0] = VersionedHash.SHA256_VERSION_ID;
    return new VersionedHash(Bytes32.wrap(dig));
  }
}
