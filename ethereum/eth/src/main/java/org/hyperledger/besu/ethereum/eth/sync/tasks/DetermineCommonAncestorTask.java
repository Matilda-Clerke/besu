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

import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.manager.exceptions.PeerDisconnectedException;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutor;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResponseCode;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResult;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetHeadersFromPeerByNumberPeerTask;
import org.hyperledger.besu.ethereum.eth.manager.task.AbstractEthTask;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.util.BlockchainUtil;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the common ancestor with the given peer. It is assumed that the peer will at least share
 * the same genesis block with this node. Running this task against a peer with a non-matching
 * genesis block will result in undefined behavior: the task may complete exceptionally or in some
 * cases this node's genesis block will be returned.
 */
public class DetermineCommonAncestorTask extends AbstractEthTask<BlockHeader> {
  private static final Logger LOG = LoggerFactory.getLogger(DetermineCommonAncestorTask.class);
  private final ProtocolSchedule protocolSchedule;
  private final ProtocolContext protocolContext;
  private final PeerTaskExecutor peerTaskExecutor;
  private final EthPeer peer;
  private final int headerRequestSize;

  private long maximumPossibleCommonAncestorNumber;
  private long minimumPossibleCommonAncestorNumber;
  private BlockHeader commonAncestorCandidate;
  private boolean initialQuery = true;

  private DetermineCommonAncestorTask(
      final ProtocolSchedule protocolSchedule,
      final ProtocolContext protocolContext,
      final PeerTaskExecutor peerTaskExecutor,
      final EthPeer peer,
      final int headerRequestSize,
      final MetricsSystem metricsSystem) {
    super(metricsSystem);
    this.protocolSchedule = protocolSchedule;
    this.protocolContext = protocolContext;
    this.peerTaskExecutor = peerTaskExecutor;
    this.peer = peer;
    this.headerRequestSize = headerRequestSize;

    maximumPossibleCommonAncestorNumber =
        Math.min(
            protocolContext.getBlockchain().getChainHeadBlockNumber(),
            peer.chainState().getEstimatedHeight());
    minimumPossibleCommonAncestorNumber = BlockHeader.GENESIS_BLOCK_NUMBER;
    commonAncestorCandidate =
        protocolContext.getBlockchain().getBlockHeader(BlockHeader.GENESIS_BLOCK_NUMBER).get();
  }

  public static DetermineCommonAncestorTask create(
      final ProtocolSchedule protocolSchedule,
      final ProtocolContext protocolContext,
      final PeerTaskExecutor peerTaskExecutor,
      final EthPeer peer,
      final int headerRequestSize,
      final MetricsSystem metricsSystem) {
    return new DetermineCommonAncestorTask(
        protocolSchedule,
        protocolContext,
        peerTaskExecutor,
        peer,
        headerRequestSize,
        metricsSystem);
  }

  @Override
  protected void executeTask() {
    while (!isTaskComplete()) {
      try {
        PeerTaskExecutorResult<List<BlockHeader>> requestHeadersResult = requestHeaders().get();
        processHeaders(requestHeadersResult);
      } catch (Exception e) {
        result.completeExceptionally(e);
        return;
      }
    }
  }

  @VisibleForTesting
  CompletableFuture<PeerTaskExecutorResult<List<BlockHeader>>> requestHeaders() {
    final long range = maximumPossibleCommonAncestorNumber - minimumPossibleCommonAncestorNumber;
    final int skipInterval = initialQuery ? 0 : calculateSkipInterval(range, headerRequestSize);
    final int count =
        initialQuery ? headerRequestSize : calculateCount((double) range, skipInterval);
    LOG.debug(
        "Searching for common ancestor with {} between {} and {}",
        peer,
        minimumPossibleCommonAncestorNumber,
        maximumPossibleCommonAncestorNumber);
    GetHeadersFromPeerByNumberPeerTask getHeadersFromPeerByNumberPeerTask =
        new GetHeadersFromPeerByNumberPeerTask(
            maximumPossibleCommonAncestorNumber,
            count,
            skipInterval,
            GetHeadersFromPeerByNumberPeerTask.Direction.BACKWARD,
            protocolSchedule);
    return peerTaskExecutor.executeAgainstPeerAsync(getHeadersFromPeerByNumberPeerTask, peer);
  }

  /**
   * In the case where the remote chain contains 100 blocks, the initial count work out to 11, and
   * the skip interval would be 9. This would yield the headers (0, 10, 20, 30, 40, 50, 60, 70, 80,
   * 90, 100).
   */
  @VisibleForTesting
  static int calculateSkipInterval(final long range, final int headerRequestSize) {
    return Math.max(0, Math.toIntExact(range / (headerRequestSize - 1) - 1) - 1);
  }

  @VisibleForTesting
  static int calculateCount(final double range, final int skipInterval) {
    return Math.toIntExact((long) Math.ceil(range / (skipInterval + 1)) + 1);
  }

  private void processHeaders(final PeerTaskExecutorResult<List<BlockHeader>> headersResult) {
    initialQuery = false;
    if (headersResult.getResponseCode() == PeerTaskExecutorResponseCode.PEER_DISCONNECTED) {
      throw new PeerDisconnectedException(peer);
    }

    if (headersResult.getResponseCode() != PeerTaskExecutorResponseCode.SUCCESS
        || headersResult.getResult().isEmpty()) {
      // Nothing to do
      return;
    }
    final List<BlockHeader> headers = headersResult.getResult().get();

    final OptionalInt maybeAncestorNumber =
        BlockchainUtil.findHighestKnownBlockIndex(protocolContext.getBlockchain(), headers, false);

    // Means the insertion point is in the next header request.
    if (maybeAncestorNumber.isEmpty()) {
      maximumPossibleCommonAncestorNumber = headers.getLast().getNumber() - 1L;
      return;
    }
    final int ancestorNumber = maybeAncestorNumber.getAsInt();
    commonAncestorCandidate = headers.get(ancestorNumber);

    if (ancestorNumber - 1 >= 0) {
      maximumPossibleCommonAncestorNumber = headers.get(ancestorNumber - 1).getNumber() - 1L;
    }
    minimumPossibleCommonAncestorNumber = headers.get(ancestorNumber).getNumber();
  }

  private boolean isTaskComplete() {
    if (maximumPossibleCommonAncestorNumber == minimumPossibleCommonAncestorNumber) {
      // Bingo, we found our common ancestor.
      result.complete(commonAncestorCandidate);
      return true;
    }
    if (maximumPossibleCommonAncestorNumber < BlockHeader.GENESIS_BLOCK_NUMBER
        && !result.isDone()) {
      result.completeExceptionally(new IllegalStateException("No common ancestor."));
      return true;
    }
    return false;
  }
}
