/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.kcctl.util;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayNameGeneration(ReplaceUnderscores.class)
class ConfigDriftTest {

    private static Map<String, String> config(String... keyValues) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put(keyValues[i], keyValues[i + 1]);
        }
        return result;
    }

    @Test
    void identical_configurations_report_no_drift() {
        Map<String, String> file = config("connector.class", "io.debezium.connector.postgresql.PostgresConnector", "tasks.max", "1");
        Map<String, String> live = config("tasks.max", "1", "connector.class", "io.debezium.connector.postgresql.PostgresConnector");

        ConfigDrift.Drift drift = ConfigDrift.compare(file, live);

        assertThat(drift.isEmpty()).isTrue();
        assertThat(drift.differenceCount()).isZero();
    }

    @Test
    void changed_value_is_reported_with_both_values() {
        ConfigDrift.Drift drift = ConfigDrift.compare(
                config("tasks.max", "1", "topic.prefix", "orders"),
                config("tasks.max", "4", "topic.prefix", "orders"));

        assertThat(drift.isEmpty()).isFalse();
        assertThat(drift.changed()).containsOnlyKeys("tasks.max");
        assertThat(drift.changed().get("tasks.max").expected()).isEqualTo("1");
        assertThat(drift.changed().get("tasks.max").actual()).isEqualTo("4");
        assertThat(drift.added()).isEmpty();
        assertThat(drift.removed()).isEmpty();
    }

    @Test
    void key_only_in_the_file_is_reported_as_removed() {
        ConfigDrift.Drift drift = ConfigDrift.compare(
                config("tasks.max", "1", "slot.name", "orders_slot"),
                config("tasks.max", "1"));

        assertThat(drift.removed()).containsExactly("slot.name");
        assertThat(drift.added()).isEmpty();
        assertThat(drift.changed()).isEmpty();
        assertThat(drift.differenceCount()).isEqualTo(1);
    }

    @Test
    void key_only_on_the_cluster_is_reported_as_added() {
        ConfigDrift.Drift drift = ConfigDrift.compare(
                config("tasks.max", "1"),
                config("tasks.max", "1", "errors.tolerance", "all"));

        assertThat(drift.added()).containsExactly("errors.tolerance");
        assertThat(drift.removed()).isEmpty();
        assertThat(drift.changed()).isEmpty();
    }

    @Test
    void several_kinds_of_difference_are_counted_together() {
        ConfigDrift.Drift drift = ConfigDrift.compare(
                config("tasks.max", "1", "slot.name", "orders_slot"),
                config("tasks.max", "2", "errors.tolerance", "all"));

        assertThat(drift.changed()).containsOnlyKeys("tasks.max");
        assertThat(drift.removed()).containsExactly("slot.name");
        assertThat(drift.added()).containsExactly("errors.tolerance");
        assertThat(drift.differenceCount()).isEqualTo(3);
    }
}
