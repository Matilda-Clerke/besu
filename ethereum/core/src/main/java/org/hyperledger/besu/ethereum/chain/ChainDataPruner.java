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
package org.hyperledger.besu.ethereum.chain;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorageTransaction;

import java.util.Collection;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChainDataPruner implements BlockAddedObserver {
  private static final Logger LOG = LoggerFactory.getLogger(ChainDataPruner.class);

  public static final int MAX_PRUNING_THREAD_QUEUE_SIZE = 16;

  private final BlockchainStorage blockchainStorage;
  private final ChainDataPrunerStorage prunerStorage;
  private final long mergeBlock;
  private final Mode mode;
  private final long blocksToRetain;
  private final long pruningFrequency;
  private final long pruningQuantity;
  private final ExecutorService pruningExecutor;

  public ChainDataPruner(
      final BlockchainStorage blockchainStorage,
      final ChainDataPrunerStorage prunerStorage,
      final long mergeBlock,
      final Mode mode,
      final long blocksToRetain,
      final long pruningFrequency,
      final long pruningQuantity,
      final ExecutorService pruningExecutor) {
    this.blockchainStorage = blockchainStorage;
    this.prunerStorage = prunerStorage;
    this.mergeBlock = mergeBlock;
    this.mode = mode;
    this.blocksToRetain = blocksToRetain;
    this.pruningFrequency = pruningFrequency;
    this.pruningExecutor = pruningExecutor;
    this.pruningQuantity = pruningQuantity;
  }

  @Override
  public void onBlockAdded(final BlockAddedEvent event) {
    switch (mode) {
      case CHAIN_PRUNING -> chainPrunerAction(event);
      case PRE_MERGE_PRUNING -> preMergePruningAction();
    }
  }

  private void chainPrunerAction(final BlockAddedEvent event) {
    final long blockNumber = event.getBlock().getHeader().getNumber();
    final long storedPruningMark = prunerStorage.getPruningMark().orElse(blockNumber);
    if (blockNumber < storedPruningMark) {
      LOG.warn(
          "Block added event: "
              + event
              + " has a block number of "
              + blockNumber
              + " < pruning mark "
              + storedPruningMark
              + " which normally indicates chain-pruning-blocks-retained is too small");
      return;
    }
    final KeyValueStorageTransaction recordBlockHashesTransaction =
        prunerStorage.startTransaction();
    final Collection<Hash> forkBlocks = prunerStorage.getForkBlocks(blockNumber);
    forkBlocks.add(event.getBlock().getHash());
    prunerStorage.setForkBlocks(recordBlockHashesTransaction, blockNumber, forkBlocks);
    recordBlockHashesTransaction.commit();

    pruningExecutor.submit(
        () -> {
          final KeyValueStorageTransaction pruningTransaction = prunerStorage.startTransaction();
          long currentPruningMark = storedPruningMark;
          final long newPruningMark = blockNumber - blocksToRetain;
          final long blocksToBePruned = newPruningMark - currentPruningMark;
          if (event.isNewCanonicalHead() && blocksToBePruned >= pruningFrequency) {
            long currentRetainedBlock = blockNumber - currentPruningMark + 1;
            while (currentRetainedBlock > blocksToRetain) {
              LOG.debug("Pruning chain data with block height of {}", currentPruningMark);
              pruneChainDataAtBlock(pruningTransaction, currentPruningMark, true);
              currentPruningMark++;
              currentRetainedBlock = blockNumber - currentPruningMark;
            }
          }
          prunerStorage.setPruningMark(pruningTransaction, currentPruningMark);
          pruningTransaction.commit();
        });
  }

  private void preMergePruningAction() {
    pruningExecutor.submit(
        () -> {
          final long storedPruningMark = prunerStorage.getPruningMark().orElse(1L);
          final long expectedNewPruningMark =
              Math.min(storedPruningMark + pruningQuantity, mergeBlock);
          final KeyValueStorageTransaction pruningTransaction = prunerStorage.startTransaction();
          for (long blockNumber = storedPruningMark;
              blockNumber < expectedNewPruningMark;
              blockNumber++) {
            pruneChainDataAtBlock(pruningTransaction, blockNumber, false);
          }
          prunerStorage.setPruningMark(pruningTransaction, expectedNewPruningMark);
          pruningTransaction.commit();
          LOG.info("Pruned blocks {} to {}", storedPruningMark, expectedNewPruningMark);
        });
  }

  private void pruneChainDataAtBlock(
      final KeyValueStorageTransaction tx, final long blockNumber, final boolean pruneHeaders) {
    final Collection<Hash> oldForkBlocks = prunerStorage.getForkBlocks(blockNumber);
    final BlockchainStorage.Updater updater = blockchainStorage.updater();
    for (final Hash toPrune : oldForkBlocks) {
      if (pruneHeaders) {
        updater.removeBlockHeader(toPrune);
      }
      updater.removeBlockBody(toPrune);
      updater.removeTransactionReceipts(toPrune);
      updater.removeTotalDifficulty(toPrune);
      blockchainStorage
          .getBlockBody(toPrune)
          .ifPresent(
              blockBody ->
                  blockBody
                      .getTransactions()
                      .forEach(t -> updater.removeTransactionLocation(t.getHash())));
    }
    updater.removeBlockHash(blockNumber);
    updater.commit();
    prunerStorage.removeForkBlocks(tx, blockNumber);
  }

  public enum Mode {
    CHAIN_PRUNING,
    PRE_MERGE_PRUNING
  }
}
