/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.ethereum.eth.sync.tasks;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toMap;

import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.manager.task.AbstractRetryingPeerTask;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public abstract class AbstractCompleteBlocksTask<T> extends AbstractRetryingPeerTask<List<T>> {

  private static final int MIN_SIZE_INCOMPLETE_LIST = 1;
  static final int DEFAULT_RETRIES = 5;

  final EthContext ethContext;
  final ProtocolSchedule protocolSchedule;
  final List<BlockHeader> headers;
  final Map<Long, T> blocks;
  final MetricsSystem metricsSystem;

  AbstractCompleteBlocksTask(
      final ProtocolSchedule protocolSchedule,
      final EthContext ethContext,
      final List<BlockHeader> headers,
      final int maxRetries,
      final MetricsSystem metricsSystem) {
    super(ethContext, maxRetries, Collection::isEmpty, metricsSystem);
    checkArgument(!headers.isEmpty(), "Must supply a non-empty headers list");
    this.protocolSchedule = protocolSchedule;
    this.ethContext = ethContext;
    this.metricsSystem = metricsSystem;
    this.headers = headers;
    this.blocks =
        headers.stream()
            .filter(BlockHeader::hasEmptyBlock)
            .collect(toMap(BlockHeader::getNumber, this::createEmptyBlock));
  }

  abstract T createEmptyBlock(BlockHeader header);

  abstract CompletableFuture<List<T>> requestBodies(Optional<EthPeer> assignedPeer);

  abstract long getBlockNumber(T block);

  @Override
  protected CompletableFuture<List<T>> executePeerTask(final Optional<EthPeer> assignedPeer) {
    return requestBodies(assignedPeer).thenCompose(this::processBodiesResult);
  }

  CompletableFuture<List<T>> processBodiesResult(final List<T> blocksResult) {
    blocksResult.forEach(block -> blocks.put(getBlockNumber(block), block));

    if (incompleteHeaders().isEmpty()) {
      result.complete(
          headers.stream().map(h -> blocks.get(h.getNumber())).collect(Collectors.toList()));
    }

    return completedFuture(blocksResult);
  }

  boolean isWithdrawalsEnabled(final BlockHeader header) {
    return header.getWithdrawalsRoot().isPresent();
  }

  List<BlockHeader> incompleteHeaders() {
    final List<BlockHeader> collectedHeaders =
        headers.stream()
            .filter(h -> blocks.get(h.getNumber()) == null)
            .collect(Collectors.toList());
    if (!collectedHeaders.isEmpty() && getRetryCount() > 1) {
      final int subSize = (int) Math.ceil((double) collectedHeaders.size() / getRetryCount());
      if (getRetryCount() > getMaxRetries()) {
        return collectedHeaders.subList(0, MIN_SIZE_INCOMPLETE_LIST);
      } else {
        return collectedHeaders.subList(0, subSize);
      }
    }

    return collectedHeaders;
  }
}
