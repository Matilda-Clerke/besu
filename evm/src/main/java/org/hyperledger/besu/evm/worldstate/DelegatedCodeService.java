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
package org.hyperledger.besu.evm.worldstate;

import static org.hyperledger.besu.evm.worldstate.DelegateCodeHelper.DELEGATED_CODE_PREFIX;
import static org.hyperledger.besu.evm.worldstate.DelegateCodeHelper.hasDelegatedCode;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.DelegatedCodeAccount;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.account.MutableDelegatedCodeAccount;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

import org.apache.tuweni.bytes.Bytes;

/** A service that manages the code injection of delegated code. */
public class DelegatedCodeService {

  private final GasCalculator gasCalculator;

  /**
   * Creates a new DelegatedCodeService.
   *
   * @param gasCalculator the gas calculator to check for pre compiles.
   */
  public DelegatedCodeService(final GasCalculator gasCalculator) {
    this.gasCalculator = gasCalculator;
  }

  /**
   * Process the delegated code authorization. It will set the code to 0x ef0100 + delegated code
   * address. If the address is 0, it will set the code to empty.
   *
   * @param account the account to which the delegated code is added.
   * @param delegatedCodeAddress the address of the target of the authorization.
   */
  public void processDelegatedCodeAuthorization(
      final MutableAccount account, final Address delegatedCodeAddress) {
    // authorization to zero address removes any delegated code
    if (delegatedCodeAddress.equals(Address.ZERO)) {
      account.setCode(Bytes.EMPTY);
      return;
    }

    account.setCode(Bytes.concatenate(DELEGATED_CODE_PREFIX, delegatedCodeAddress));
  }

  /**
   * Returns if the provided account has either no code set or has already delegated code.
   *
   * @param account the account to check.
   * @return {@code true} if the account can set delegated code, {@code false} otherwise.
   */
  public boolean canSetDelegatedCode(final Account account) {
    return account.getCode().isEmpty() || hasDelegatedCode(account.getCode());
  }

  /**
   * Processes the provided account, resolving the code if delegated.
   *
   * @param worldUpdater the world updater to retrieve the delegated code.
   * @param account the account to process.
   * @return the processed account, containing the delegated code if set, the unmodified account
   *     otherwise.
   */
  public Account processAccount(final WorldUpdater worldUpdater, final Account account) {
    if (account == null || !hasDelegatedCode(account.getCode())) {
      return account;
    }

    return new DelegatedCodeAccount(
        worldUpdater, account, resolveDelegatedAddress(account.getCode()), gasCalculator);
  }

  /**
   * Processes the provided mutable account, resolving the code if delegated.
   *
   * @param worldUpdater the world updater to retrieve the delegated code.
   * @param account the mutable account to process.
   * @return the processed mutable account, containing the delegated code if set, the unmodified
   *     mutable account otherwise.
   */
  public MutableAccount processMutableAccount(
      final WorldUpdater worldUpdater, final MutableAccount account) {
    if (account == null || !hasDelegatedCode(account.getCode())) {
      return account;
    }

    return new MutableDelegatedCodeAccount(
        worldUpdater, account, resolveDelegatedAddress(account.getCode()), gasCalculator);
  }

  private Address resolveDelegatedAddress(final Bytes code) {
    return Address.wrap(code.slice(DELEGATED_CODE_PREFIX.size()));
  }
}
