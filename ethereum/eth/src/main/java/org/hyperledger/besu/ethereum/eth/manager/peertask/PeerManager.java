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
import org.hyperledger.besu.ethereum.p2p.peers.PeerId;

import java.util.Optional;
import java.util.function.Predicate;

/** "Manages" the EthPeers for the PeerTaskExecutor */
public interface PeerManager {

  /**
   * Gets the highest reputation peer matching the supplies filter
   *
   * @param filter a filter to match prospective peers with
   * @return the highest reputation peer matching the supplies filter
   * @throws NoAvailablePeerException If there are no suitable peers
   */
  EthPeer getPeer(final Predicate<EthPeer> filter) throws NoAvailablePeerException;

  /**
   * Attempts to get the EthPeer identified by peerId
   *
   * @param peerId the peerId of the desired EthPeer
   * @return An Optional\<EthPeer\> containing the EthPeer identified by peerId if present in the
   *     PeerManager, or empty otherwise
   */
  Optional<EthPeer> getPeerByPeerId(final PeerId peerId);

  /**
   * Add the supplied EthPeer to the PeerManager
   *
   * @param ethPeer the EthPeer to be added to the PeerManager
   */
  void addPeer(final EthPeer ethPeer);

  /**
   * Remove the EthPeer identified by peerId from the PeerManager
   *
   * @param peerId the PeerId of the EthPeer to be removed from the PeerManager
   */
  void removePeer(final PeerId peerId);
}
