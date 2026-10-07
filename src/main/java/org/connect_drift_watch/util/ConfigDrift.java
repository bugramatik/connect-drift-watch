/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.kcctl.util;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compares the configuration a connector was registered from with the configuration the
 * cluster currently reports for it.
 */
public final class ConfigDrift {

    private ConfigDrift() {
    }

    /**
     * The outcome of comparing two connector configurations.
     *
     * @param added keys that exist on the cluster but not in the file
     * @param removed keys that exist in the file but not on the cluster
     * @param changed keys present in both whose values differ, mapped to the file value and the live value
     */
    public record Drift(SortedSet<String> added, SortedSet<String> removed, SortedMap<String, Change> changed) {

        public Drift {
            added = Collections.unmodifiableSortedSet(new TreeSet<>(added));
            removed = Collections.unmodifiableSortedSet(new TreeSet<>(removed));
            changed = Collections.unmodifiableSortedMap(new TreeMap<>(changed));
        }

        public static Drift none() {
            return new Drift(Set.of(), Set.of(), Map.of());
        }

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        }

        public int differenceCount() {
            return added.size() + removed.size() + changed.size();
        }
    }

    /**
     * A single property whose value differs between the file and the cluster.
     *
     * @param expected the value in the file the connector was registered from
     * @param actual the value the cluster reports
     */
    public record Change(String expected, String actual) {
    }

    /**
     * Compares a connector's file configuration with its live configuration.
     *
     * @param fileConfig the configuration read from the connector's JSON file; may not be null
     * @param liveConfig the configuration returned by the cluster; may not be null
     * @return the keys that were added, removed or changed; never null
     */
    public static Drift compare(Map<String, String> fileConfig, Map<String, String> liveConfig) {
        Objects.requireNonNull(fileConfig, "fileConfig may not be null");
        Objects.requireNonNull(liveConfig, "liveConfig may not be null");

        SortedSet<String> added = new TreeSet<>();
        SortedSet<String> removed = new TreeSet<>();
        SortedMap<String, Change> changed = new TreeMap<>();

        for (Map.Entry<String, String> entry : liveConfig.entrySet()) {
            String key = entry.getKey();
            if (!fileConfig.containsKey(key)) {
                added.add(key);
                continue;
            }
            String expected = fileConfig.get(key);
            String actual = entry.getValue();
            if (!Objects.equals(expected, actual)) {
                changed.put(key, new Change(expected, actual));
            }
        }

        for (String key : fileConfig.keySet()) {
            if (!liveConfig.containsKey(key)) {
                removed.add(key);
            }
        }

        return new Drift(added, removed, changed);
    }
}
