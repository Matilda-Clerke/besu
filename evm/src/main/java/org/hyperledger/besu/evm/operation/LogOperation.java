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
package org.hyperledger.besu.evm.operation;

import static org.apache.tuweni.bytes.Bytes32.leftPad;
import static org.hyperledger.besu.evm.internal.Words.clampedToLong;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.log.Log;
import org.hyperledger.besu.evm.log.LogTopic;

import com.google.common.collect.ImmutableList;
import org.apache.tuweni.bytes.Bytes;

/** The Log operation. */
public class LogOperation extends AbstractOperation {

  private final int numTopics;

  /**
   * Instantiates a new Log operation.
   *
   * @param numTopics the num topics
   * @param gasCalculator the gas calculator
   */
  public LogOperation(final int numTopics, final GasCalculator gasCalculator) {
    super(0xA0 + numTopics, "LOG" + numTopics, numTopics + 2, 0, gasCalculator);
    this.numTopics = numTopics;
  }

  @Override
  public OperationResult execute(final MessageFrame frame, final EVM evm) {
    final long dataLocation = clampedToLong(frame.popStackItem());
    final long numBytes = clampedToLong(frame.popStackItem());

    if (frame.isStatic()) {
      return new OperationResult(0, ExceptionalHaltReason.ILLEGAL_STATE_CHANGE);
    }

    final long cost = gasCalculator().logOperationGasCost(frame, dataLocation, numBytes, numTopics);
    if (frame.getRemainingGas() < cost) {
      return new OperationResult(cost, ExceptionalHaltReason.INSUFFICIENT_GAS);
    }

    final Address address = frame.getRecipientAddress();

    final Bytes data = frame.readMemory(dataLocation, numBytes);

    final ImmutableList.Builder<LogTopic> builder =
        ImmutableList.builderWithExpectedSize(numTopics);
    for (int i = 0; i < numTopics; i++) {
      builder.add(LogTopic.create(leftPad(frame.popStackItem())));
    }

    frame.addLog(new Log(address, data, builder.build()));
    return new OperationResult(cost, null);
  }
}
