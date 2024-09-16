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
package org.hyperledger.besu.ethereum.eth.peervalidation;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockDataGenerator;
import org.hyperledger.besu.ethereum.core.BlockDataGenerator.BlockOptions;
import org.hyperledger.besu.ethereum.core.ProtocolScheduleFixture;
import org.hyperledger.besu.ethereum.eth.manager.EthProtocolManager;
import org.hyperledger.besu.ethereum.eth.manager.EthProtocolManagerTestUtil;
import org.hyperledger.besu.ethereum.eth.manager.RespondingEthPeer;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutor;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResponseCode;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResult;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetHeadersFromPeerByNumberPeerTask;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class RequiredBlocksPeerValidatorTest extends AbstractPeerBlockValidatorTest {

  @Override
  AbstractPeerBlockValidator createValidator(
      final PeerTaskExecutor peerTaskExecutor, final long blockNumber, final long buffer) {
    return new RequiredBlocksPeerValidator(
        ProtocolScheduleFixture.MAINNET, peerTaskExecutor, blockNumber, Hash.ZERO, buffer);
  }

  @Test
  public void validatePeer_responsivePeerWithRequiredBlock() {
    final EthProtocolManager ethProtocolManager = EthProtocolManagerTestUtil.create();
    final BlockDataGenerator gen = new BlockDataGenerator(1);
    final long requiredBlockNumber = 500;
    final Block requiredBlock =
        gen.block(BlockOptions.create().setBlockNumber(requiredBlockNumber));

    final PeerValidator validator =
        new RequiredBlocksPeerValidator(
            ProtocolScheduleFixture.MAINNET,
            peerTaskExecutor,
            requiredBlockNumber,
            requiredBlock.getHash(),
            0);

    final RespondingEthPeer peer =
        EthProtocolManagerTestUtil.createPeer(ethProtocolManager, requiredBlockNumber);

    Mockito.when(
            peerTaskExecutor.executeAgainstPeer(
                Mockito.any(GetHeadersFromPeerByNumberPeerTask.class),
                Mockito.eq(peer.getEthPeer())))
        .thenReturn(
            new PeerTaskExecutorResult<>(
                List.of(requiredBlock.getHeader()), PeerTaskExecutorResponseCode.SUCCESS));

    final CompletableFuture<Boolean> result =
        validator.validatePeer(ethProtocolManager.ethContext(), peer.getEthPeer());

    assertThat(result).isDone();
    assertThat(result).isCompletedWithValue(true);
  }

  @Test
  public void validatePeer_responsivePeerWithBadRequiredBlock() {
    final EthProtocolManager ethProtocolManager = EthProtocolManagerTestUtil.create();
    final BlockDataGenerator gen = new BlockDataGenerator(1);
    final long requiredBlockNumber = 500;
    final Block requiredBlock =
        gen.block(BlockOptions.create().setBlockNumber(requiredBlockNumber));

    final PeerValidator validator =
        new RequiredBlocksPeerValidator(
            ProtocolScheduleFixture.MAINNET, peerTaskExecutor, requiredBlockNumber, Hash.ZERO, 0);

    final RespondingEthPeer peer =
        EthProtocolManagerTestUtil.createPeer(ethProtocolManager, requiredBlockNumber);

    Mockito.when(
            peerTaskExecutor.executeAgainstPeer(
                Mockito.any(GetHeadersFromPeerByNumberPeerTask.class),
                Mockito.eq(peer.getEthPeer())))
        .thenReturn(
            new PeerTaskExecutorResult<>(
                List.of(requiredBlock.getHeader()), PeerTaskExecutorResponseCode.SUCCESS));

    final CompletableFuture<Boolean> result =
        validator.validatePeer(ethProtocolManager.ethContext(), peer.getEthPeer());

    assertThat(result).isDone();
    assertThat(result).isCompletedWithValue(false);
  }

  @Test
  public void validatePeer_responsivePeerDoesNotHaveBlockWhenPastForkHeight() {
    final EthProtocolManager ethProtocolManager = EthProtocolManagerTestUtil.create();

    final PeerValidator validator =
        new RequiredBlocksPeerValidator(
            ProtocolScheduleFixture.MAINNET, peerTaskExecutor, 1, Hash.ZERO);

    final RespondingEthPeer peer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 1);

    Mockito.when(
            peerTaskExecutor.executeAgainstPeer(
                Mockito.any(GetHeadersFromPeerByNumberPeerTask.class),
                Mockito.eq(peer.getEthPeer())))
        .thenReturn(
            new PeerTaskExecutorResult<>(
                Collections.emptyList(), PeerTaskExecutorResponseCode.SUCCESS));

    final CompletableFuture<Boolean> result =
        validator.validatePeer(ethProtocolManager.ethContext(), peer.getEthPeer());

    assertThat(result).isDone();
    assertThat(result).isCompletedWithValue(false);
  }
}
