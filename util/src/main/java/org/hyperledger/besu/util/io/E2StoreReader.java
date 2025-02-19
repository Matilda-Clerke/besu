/*
 * Copyright contributors to Besu.
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
package org.hyperledger.besu.util.io;

import org.hyperledger.besu.util.e2.E2BeaconState;
import org.hyperledger.besu.util.e2.E2BlockIndex;
import org.hyperledger.besu.util.e2.E2ExecutionBlockBody;
import org.hyperledger.besu.util.e2.E2ExecutionBlockHeader;
import org.hyperledger.besu.util.e2.E2ExecutionBlockReceipts;
import org.hyperledger.besu.util.e2.E2SignedBeaconBlock;
import org.hyperledger.besu.util.e2.E2SlotIndex;
import org.hyperledger.besu.util.e2.E2StoreReaderListener;
import org.hyperledger.besu.util.e2.E2Type;
import org.hyperledger.besu.util.snappy.SnappyFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import com.google.common.primitives.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xerial.snappy.SnappyFramedInputStream;

public class E2StoreReader {
  private static final Logger LOG = LoggerFactory.getLogger(E2StoreReader.class);
  private static final int TYPE_LENGTH = 2;
  private static final int LENGTH_LENGTH = 6;
  private static final int STARTING_SLOT_LENGTH = 8;
  private static final int SLOT_INDEX_LENGTH = 8;
  private static final int SLOT_INDEX_COUNT_LENGTH = 8;

  private final SnappyFactory snappyFactory;

  public E2StoreReader(final SnappyFactory snappyFactory) {
    this.snappyFactory = snappyFactory;
  }

  public void read(final InputStream inputStream, final E2StoreReaderListener listener)
      throws IOException {
    int slot = 0;
    while (inputStream.available() > 0) {
      E2Type type = E2Type.getForTypeCode(inputStream.readNBytes(TYPE_LENGTH));
      int length = (int) convertLittleEndianBytesToLong(inputStream.readNBytes(LENGTH_LENGTH));
      switch (type) {
        case VERSION -> {
          // do nothing
        }
        case EMPTY, ACCUMULATOR, TOTAL_DIFFICULTY -> {
          // skip the bytes that were indicated to be empty
          // TODO read ACCUMULATOR and TOTAL_DIFFICULTY properly?
          inputStream.skipNBytes(length);
        }
        case SLOT_INDEX -> {
          ByteArrayInputStream slotIndexInputStream =
              new ByteArrayInputStream(inputStream.readNBytes(length));
          long startingSlot =
              convertLittleEndianBytesToLong(slotIndexInputStream.readNBytes(STARTING_SLOT_LENGTH));
          List<Long> indexes = new ArrayList<>();
          while (slotIndexInputStream.available() > SLOT_INDEX_COUNT_LENGTH) {
            indexes.add(
                convertLittleEndianBytesToLong(slotIndexInputStream.readNBytes(SLOT_INDEX_LENGTH)));
          }
          long indexCount =
              convertLittleEndianBytesToLong(
                  slotIndexInputStream.readNBytes(SLOT_INDEX_COUNT_LENGTH));
          if (indexCount != indexes.size()) {
            LOG.warn(
                "index count does not match number of indexes present for InputStream: {}",
                inputStream);
          }
          listener.handleSlotIndex(new E2SlotIndex(startingSlot, indexes));
        }
        case COMPRESSED_BEACON_STATE -> {
          byte[] compressedBeaconStateArray = inputStream.readNBytes(length);
          try (SnappyFramedInputStream decompressionStream =
              snappyFactory.createFramedInputStream(compressedBeaconStateArray)) {
            // TODO: decode with SSZ
            listener.handleBeaconState(
                new E2BeaconState(decompressionStream.readAllBytes(), slot++));
          }
        }
        case COMPRESSED_SIGNED_BEACON_BLOCK -> {
          byte[] compressedSignedBeaconBlockArray = inputStream.readNBytes(length);
          try (SnappyFramedInputStream decompressionStream =
              snappyFactory.createFramedInputStream(compressedSignedBeaconBlockArray)) {
            // TODO: decode with SSZ
            listener.handleSignedBeaconBlock(
                new E2SignedBeaconBlock(decompressionStream.readAllBytes(), slot++));
          }
        }
        case COMPRESSED_EXECUTION_BLOCK_HEADER -> {
          byte[] compressedExecutionBlockHeader = inputStream.readNBytes(length);
          try (SnappyFramedInputStream decompressionStream =
              snappyFactory.createFramedInputStream(compressedExecutionBlockHeader)) {
            listener.handleExecutionBlockHeader(
                new E2ExecutionBlockHeader(decompressionStream.readAllBytes(), slot));
          }
        }
        case COMPRESSED_EXECUTION_BLOCK_BODY -> {
          byte[] compressedExecutionBlock = inputStream.readNBytes(length);
          try (SnappyFramedInputStream decompressionStream =
              snappyFactory.createFramedInputStream(compressedExecutionBlock)) {
            listener.handleExecutionBlockBody(
                new E2ExecutionBlockBody(decompressionStream.readAllBytes(), slot));
          }
        }
        case COMPRESSED_EXECUTION_BLOCK_RECEIPTS -> {
          byte[] compressedReceipts = inputStream.readNBytes(length);
          try (SnappyFramedInputStream decompressionStream =
              snappyFactory.createFramedInputStream(compressedReceipts)) {
            listener.handleExecutionBlockReceipts(
                new E2ExecutionBlockReceipts(decompressionStream.readAllBytes(), slot++));
          }
        }
        case BLOCK_INDEX -> {
          ByteArrayInputStream slotIndexInputStream =
              new ByteArrayInputStream(inputStream.readNBytes(length));
          long startingSlot =
              convertLittleEndianBytesToLong(slotIndexInputStream.readNBytes(STARTING_SLOT_LENGTH));
          List<Long> indexes = new ArrayList<>();
          while (slotIndexInputStream.available() > SLOT_INDEX_COUNT_LENGTH) {
            indexes.add(
                convertLittleEndianBytesToLong(slotIndexInputStream.readNBytes(SLOT_INDEX_LENGTH)));
          }
          long indexCount =
              convertLittleEndianBytesToLong(
                  slotIndexInputStream.readNBytes(SLOT_INDEX_COUNT_LENGTH));
          if (indexCount != indexes.size()) {
            LOG.warn(
                "index count does not match number of indexes present for InputStream: {}",
                inputStream);
          }
          listener.handleBlockIndex(new E2BlockIndex(startingSlot, indexes));
        }
      }
    }
  }

  private long convertLittleEndianBytesToLong(final byte[] bytes) {
    int additionalBytes = Long.BYTES - bytes.length;
    return ByteBuffer.wrap(Bytes.concat(bytes, new byte[additionalBytes]))
        .order(ByteOrder.LITTLE_ENDIAN)
        .getLong();
  }
}
