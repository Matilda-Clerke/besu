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
package org.hyperledger.besu.metrics.prometheus;

import static org.hyperledger.besu.metrics.prometheus.PrometheusCollector.getLabelValues;

import org.hyperledger.besu.metrics.Observation;
import org.hyperledger.besu.plugin.services.metrics.LabelledSuppliedMetric;
import org.hyperledger.besu.plugin.services.metrics.MetricCategory;

import java.util.List;

import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import io.prometheus.metrics.model.registry.Collector;
import io.prometheus.metrics.model.snapshots.DataPointSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;

/**
 * A Prometheus supplied gauge collector. A supplied gauge collector is one which actual value is
 * kept outside the metric system, for example in an external library or to calculate the value only
 * on demand when a metric scrape occurs.
 */
@SuppressWarnings("removal")
class PrometheusSuppliedGauge extends AbstractPrometheusSuppliedValueCollector
    implements LabelledSuppliedMetric {

  public PrometheusSuppliedGauge(
      final MetricCategory category,
      final String name,
      final String help,
      final String... labelNames) {
    super(category, name, help, labelNames);
  }

  @Override
  protected Collector createCollector(final String help, final String... labelNames) {
    return GaugeWithCallback.builder()
        .name(this.prefixedName)
        .help(help)
        .labelNames(labelNames)
        .callback(this::callback)
        .build();
  }

  private void callback(final GaugeWithCallback.Callback callback) {
    labelledCallbackData
        .values()
        .forEach(
            callbackData ->
                callback.call(
                    callbackData.valueSupplier().getAsDouble(), callbackData.labelValues()));
  }

  @Override
  protected Observation convertToObservation(final DataPointSnapshot sample) {
    final List<String> labelValues = getLabelValues(sample.getLabels());

    return new Observation(
        category, name, ((GaugeSnapshot.GaugeDataPointSnapshot) sample).getValue(), labelValues);
  }
}
