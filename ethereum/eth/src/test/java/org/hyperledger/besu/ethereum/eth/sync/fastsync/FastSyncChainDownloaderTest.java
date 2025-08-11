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
package org.hyperledger.besu.ethereum.eth.sync.fastsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.BlockchainSetupUtil;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.manager.EthProtocolManager;
import org.hyperledger.besu.ethereum.eth.manager.EthProtocolManagerTestBuilder;
import org.hyperledger.besu.ethereum.eth.manager.EthProtocolManagerTestUtil;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler;
import org.hyperledger.besu.ethereum.eth.manager.RespondingEthPeer;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutor;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetBodiesFromPeerTask;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetBodiesFromPeerTaskExecutorAnswer;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetHeadersFromPeerTask;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetHeadersFromPeerTaskExecutorAnswer;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetReceiptsFromPeerTask;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetReceiptsFromPeerTaskExecutorAnswer;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetSyncBlockBodiesFromPeerTask;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetSyncBlockBodiesFromPeerTaskExecutorAnswer;
import org.hyperledger.besu.ethereum.eth.sync.ChainDownloader;
import org.hyperledger.besu.ethereum.eth.sync.SynchronizerConfiguration;
import org.hyperledger.besu.ethereum.eth.sync.state.SyncState;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorageCoordinator;
import org.hyperledger.besu.metrics.SyncDurationMetrics;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.storage.DataStorageFormat;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.Mockito;

public class FastSyncChainDownloaderTest {

  private final WorldStateStorageCoordinator worldStateStorageCoordinator =
      mock(WorldStateStorageCoordinator.class);

  protected ProtocolSchedule protocolSchedule;
  protected EthProtocolManager ethProtocolManager;
  protected EthContext ethContext;
  protected ProtocolContext protocolContext;
  private SyncState syncState;
  private PeerTaskExecutor peerTaskExecutor;

  protected MutableBlockchain localBlockchain;
  private BlockchainSetupUtil otherBlockchainSetup;
  protected Blockchain otherBlockchain;

  static class FastSyncChainDownloaderTestArguments implements ArgumentsProvider {
    @Override
    public Stream<? extends Arguments> provideArguments(final ExtensionContext context) {
      return Stream.of(
          Arguments.of(DataStorageFormat.BONSAI), Arguments.of(DataStorageFormat.FOREST));
    }
  }

  public void setup(final DataStorageFormat storageFormat) {
    when(worldStateStorageCoordinator.getDataStorageFormat()).thenReturn(storageFormat);
    when(worldStateStorageCoordinator.isWorldStateAvailable(any(), any())).thenReturn(true);
    final BlockchainSetupUtil localBlockchainSetup = BlockchainSetupUtil.forTesting(storageFormat);
    localBlockchain = localBlockchainSetup.getBlockchain();
    otherBlockchainSetup = BlockchainSetupUtil.forTesting(storageFormat);
    otherBlockchain = otherBlockchainSetup.getBlockchain();

    peerTaskExecutor = Mockito.mock(PeerTaskExecutor.class);
    protocolSchedule = localBlockchainSetup.getProtocolSchedule();
    protocolContext = localBlockchainSetup.getProtocolContext();
    ethProtocolManager =
        EthProtocolManagerTestBuilder.builder()
            .setProtocolSchedule(protocolSchedule)
            .setBlockchain(localBlockchain)
            .setEthScheduler(new EthScheduler(1, 1, 1, 1, new NoOpMetricsSystem()))
            .setPeerTaskExecutor(peerTaskExecutor)
            .build();

    ethContext = ethProtocolManager.ethContext();
    syncState = new SyncState(protocolContext.getBlockchain(), ethContext.getEthPeers());

    Mockito.when(peerTaskExecutor.execute(Mockito.any(GetHeadersFromPeerTask.class)))
        .thenAnswer(
            new GetHeadersFromPeerTaskExecutorAnswer(otherBlockchain, ethContext.getEthPeers()));
    Mockito.when(
            peerTaskExecutor.executeAgainstPeer(
                Mockito.any(GetHeadersFromPeerTask.class), Mockito.any(EthPeer.class)))
        .thenAnswer(
            new GetHeadersFromPeerTaskExecutorAnswer(otherBlockchain, ethContext.getEthPeers()));
    Mockito.when(peerTaskExecutor.execute(Mockito.any(GetBodiesFromPeerTask.class)))
        .thenAnswer(
            new GetBodiesFromPeerTaskExecutorAnswer(otherBlockchain, ethContext.getEthPeers()));
    Mockito.when(
            peerTaskExecutor.executeAgainstPeer(
                Mockito.any(GetBodiesFromPeerTask.class), Mockito.any(EthPeer.class)))
        .thenAnswer(
            new GetBodiesFromPeerTaskExecutorAnswer(otherBlockchain, ethContext.getEthPeers()));
    Mockito.when(peerTaskExecutor.execute(Mockito.any(GetSyncBlockBodiesFromPeerTask.class)))
        .thenAnswer(
            new GetSyncBlockBodiesFromPeerTaskExecutorAnswer(
                otherBlockchain, ethContext.getEthPeers(), protocolSchedule));
    Mockito.when(
            peerTaskExecutor.executeAgainstPeer(
                Mockito.any(GetSyncBlockBodiesFromPeerTask.class), Mockito.any(EthPeer.class)))
        .thenAnswer(
            new GetSyncBlockBodiesFromPeerTaskExecutorAnswer(
                otherBlockchain, ethContext.getEthPeers(), protocolSchedule));
    Mockito.when(peerTaskExecutor.execute(Mockito.any(GetReceiptsFromPeerTask.class)))
        .thenAnswer(
            new GetReceiptsFromPeerTaskExecutorAnswer(otherBlockchain, ethContext.getEthPeers()));
    when(peerTaskExecutor.executeAgainstPeer(
            any(GetReceiptsFromPeerTask.class), any(EthPeer.class)))
        .thenAnswer(
            new GetReceiptsFromPeerTaskExecutorAnswer(otherBlockchain, ethContext.getEthPeers()));
  }

  @AfterEach
  public void tearDown() {
    if (ethProtocolManager != null) {
      ethProtocolManager.stop();
    }
  }

  private ChainDownloader downloader(
      final SynchronizerConfiguration syncConfig, final long pivotBlockNumber) {
    return FastSyncChainDownloader.create(
        syncConfig,
        worldStateStorageCoordinator,
        protocolSchedule,
        protocolContext,
        ethContext,
        syncState,
        new NoOpMetricsSystem(),
        new FastSyncState(otherBlockchain.getBlockHeader(pivotBlockNumber).get()),
        SyncDurationMetrics.NO_OP_SYNC_DURATION_METRICS);
  }

  @ParameterizedTest
  @ArgumentsSource(FastSyncChainDownloaderTestArguments.class)
  public void shouldSyncToPivotBlockInMultipleSegments(final DataStorageFormat storageFormat) {
    setup(storageFormat);
    otherBlockchainSetup.importFirstBlocks(30);

    final RespondingEthPeer peer =
        EthProtocolManagerTestUtil.createPeer(ethProtocolManager, otherBlockchain);
    final RespondingEthPeer.Responder responder =
        RespondingEthPeer.blockchainResponder(otherBlockchain);

    final SynchronizerConfiguration syncConfig =
        SynchronizerConfiguration.builder()
            .downloaderChainSegmentSize(5)
            .downloaderHeadersRequestSize(3)
            .build();
    final long pivotBlockNumber = 25;
    final ChainDownloader downloader = downloader(syncConfig, pivotBlockNumber);
    final CompletableFuture<Void> result = downloader.start();

    peer.respondWhileOtherThreadsWork(responder, () -> !result.isDone());

    assertThat(result).isCompleted();
    assertThat(localBlockchain.getChainHeadBlockNumber()).isEqualTo(pivotBlockNumber);
    assertThat(localBlockchain.getChainHeadHeader())
        .isEqualTo(otherBlockchain.getBlockHeader(pivotBlockNumber).get());
  }

  @ParameterizedTest
  @ArgumentsSource(FastSyncChainDownloaderTestArguments.class)
  public void shouldSyncToPivotBlockInSingleSegment(final DataStorageFormat storageFormat) {
    setup(storageFormat);
    otherBlockchainSetup.importFirstBlocks(30);

    final RespondingEthPeer peer =
        EthProtocolManagerTestUtil.createPeer(ethProtocolManager, otherBlockchain);
    final RespondingEthPeer.Responder responder =
        RespondingEthPeer.blockchainResponder(otherBlockchain);

    final long pivotBlockNumber = 5;
    final SynchronizerConfiguration syncConfig = SynchronizerConfiguration.builder().build();
    final ChainDownloader downloader = downloader(syncConfig, pivotBlockNumber);
    final CompletableFuture<Void> result = downloader.start();

    peer.respondWhileOtherThreadsWork(responder, () -> !result.isDone());

    assertThat(result).isCompleted();
    assertThat(localBlockchain.getChainHeadBlockNumber()).isEqualTo(pivotBlockNumber);
    assertThat(localBlockchain.getChainHeadHeader())
        .isEqualTo(otherBlockchain.getBlockHeader(pivotBlockNumber).get());
  }

  @Test
  void dryRunDetector() {
    assertThat(true)
        .withFailMessage("This test is here so gradle --dry-run executes this class")
        .isTrue();
  }
}
