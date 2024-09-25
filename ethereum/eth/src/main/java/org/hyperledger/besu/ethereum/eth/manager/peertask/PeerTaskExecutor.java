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
package org.hyperledger.besu.ethereum.eth.manager.peertask;

import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.p2p.rlpx.connections.PeerConnection;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData;
import org.hyperledger.besu.metrics.BesuMetricCategory;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;
import org.hyperledger.besu.plugin.services.metrics.OperationTimer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Manages the execution of PeerTasks, respecting their PeerTaskBehavior */
public class PeerTaskExecutor {

  private final PeerSelector peerSelector;
  private final PeerTaskRequestSender requestSender;
  private final Supplier<ProtocolSpec> protocolSpecSupplier;
  private final LabelledMetric<OperationTimer> requestTimer;

  public PeerTaskExecutor(
      final PeerSelector peerSelector,
      final PeerTaskRequestSender requestSender,
      final Supplier<ProtocolSpec> protocolSpecSupplier,
      final MetricsSystem metricsSystem) {
    this.peerSelector = peerSelector;
    this.requestSender = requestSender;
    this.protocolSpecSupplier = protocolSpecSupplier;
    requestTimer =
        metricsSystem.createLabelledTimer(
            BesuMetricCategory.PEERS, "Peer Task Executor Request Time", "", "Task Class Name");
  }

  public <T> PeerTaskExecutorResult<T> execute(final PeerTask<T> peerTask) {
    PeerTaskExecutorResult<T> executorResult;
    int triesRemaining =
        peerTask.getPeerTaskBehaviors().contains(PeerTaskBehavior.RETRY_WITH_OTHER_PEERS) ? 3 : 1;
    final Collection<EthPeer> usedEthPeers = new ArrayList<>();
    do {
      EthPeer peer;
      try {
        peer =
            peerSelector.getPeer(
                (candidatePeer) ->
                    isPeerUnused(candidatePeer, usedEthPeers)
                        && (protocolSpecSupplier.get().isPoS()
                            || isPeerHeightHighEnough(
                                candidatePeer, peerTask.getRequiredBlockNumber()))
                        && isPeerProtocolSuitable(candidatePeer, peerTask.getSubProtocol()));
        usedEthPeers.add(peer);
        executorResult = executeAgainstPeer(peerTask, peer);
      } catch (NoAvailablePeerException e) {
        executorResult =
            new PeerTaskExecutorResult<>(null, PeerTaskExecutorResponseCode.NO_PEER_AVAILABLE);
      }
    } while (--triesRemaining > 0
        && executorResult.getResponseCode() != PeerTaskExecutorResponseCode.SUCCESS);

    return executorResult;
  }

  public <T> CompletableFuture<PeerTaskExecutorResult<T>> executeAsync(final PeerTask<T> peerTask) {
    return CompletableFuture.supplyAsync(() -> execute(peerTask));
  }

  public <T> PeerTaskExecutorResult<T> executeAgainstPeer(
      final PeerTask<T> peerTask, final EthPeer peer) {
    MessageData requestMessageData = peerTask.getRequestMessage();
    PeerTaskExecutorResult<T> executorResult;
    int triesRemaining =
        peerTask.getPeerTaskBehaviors().contains(PeerTaskBehavior.RETRY_WITH_SAME_PEER) ? 3 : 1;
    do {
      try {

        MessageData responseMessageData;
        try (final OperationTimer.TimingContext timingContext =
            requestTimer.labels(peerTask.getClass().getSimpleName()).startTimer()) {
          responseMessageData =
              requestSender.sendRequest(peerTask.getSubProtocol(), requestMessageData, peer);
        }
        T result = peerTask.parseResponse(responseMessageData);
        peer.recordUsefulResponse();
        executorResult = new PeerTaskExecutorResult<>(result, PeerTaskExecutorResponseCode.SUCCESS);

      } catch (PeerConnection.PeerNotConnected e) {
        executorResult =
            new PeerTaskExecutorResult<>(null, PeerTaskExecutorResponseCode.PEER_DISCONNECTED);

      } catch (InterruptedException | TimeoutException e) {
        peer.recordRequestTimeout(requestMessageData.getCode());
        executorResult = new PeerTaskExecutorResult<>(null, PeerTaskExecutorResponseCode.TIMEOUT);

      } catch (InvalidPeerTaskResponseException e) {
        peer.recordUselessResponse(e.getMessage());
        executorResult =
            new PeerTaskExecutorResult<>(null, PeerTaskExecutorResponseCode.INVALID_RESPONSE);

      } catch (ExecutionException e) {
        executorResult =
            new PeerTaskExecutorResult<>(null, PeerTaskExecutorResponseCode.INTERNAL_SERVER_ERROR);
      }
    } while (--triesRemaining > 0
        && executorResult.getResponseCode() != PeerTaskExecutorResponseCode.SUCCESS
        && executorResult.getResponseCode() != PeerTaskExecutorResponseCode.PEER_DISCONNECTED
        && sleepBetweenRetries());

    return executorResult;
  }

  public <T> CompletableFuture<PeerTaskExecutorResult<T>> executeAgainstPeerAsync(
      final PeerTask<T> peerTask, final EthPeer peer) {
    return CompletableFuture.supplyAsync(() -> executeAgainstPeer(peerTask, peer));
  }

  private boolean sleepBetweenRetries() {
    try {
      // sleep for 1 second to match implemented wait between retries in AbstractRetryingPeerTask
      Thread.sleep(1000);
      return true;
    } catch (InterruptedException e) {
      return false;
    }
  }

  private static boolean isPeerUnused(
      final EthPeer ethPeer, final Collection<EthPeer> usedEthPeers) {
    return !usedEthPeers.contains(ethPeer);
  }

  private static boolean isPeerHeightHighEnough(final EthPeer ethPeer, final long requiredHeight) {
    return ethPeer.chainState().getEstimatedHeight() >= requiredHeight;
  }

  private static boolean isPeerProtocolSuitable(final EthPeer ethPeer, final String protocol) {
    return ethPeer.getProtocolName().equals(protocol);
  }
}
