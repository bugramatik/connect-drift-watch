/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.kcctl.command;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.kcctl.service.KafkaConnectApi;
import org.kcctl.service.KafkaConnectException;
import org.kcctl.service.KafkaConnectNotFoundException;
import org.kcctl.util.ConfigDrift;
import org.kcctl.util.ConfigurationContext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import static org.kcctl.util.Colors.ANSI_RESET;
import static org.kcctl.util.Colors.ANSI_WHITE_BOLD;

@Command(name = "drift", description = "Compares the live configuration of connectors with the files they were registered from")
public class DriftCommand implements Callable<Integer> {

    @CommandLine.Mixin
    HelpMixin help;

    @Parameters(paramLabel = "FILE", arity = "1..*", description = "One or more connector JSON files to check against the cluster")
    List<File> files = new ArrayList<>();

    @Option(names = { "-q", "--quiet" }, description = "Only prints connectors whose configuration has drifted")
    boolean quiet;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    private static final ObjectMapper mapper = new ObjectMapper();

    private final ConfigurationContext context;

    @Inject
    public DriftCommand(ConfigurationContext context) {
        this.context = context;
    }

    // Hack : Picocli currently require an empty constructor to generate the completion file
    public DriftCommand() {
        context = new ConfigurationContext();
    }

    @Override
    public Integer call() {
        KafkaConnectApi kafkaConnectApi = RestClientBuilder.newBuilder()
                .baseUri(context.getCurrentContext().getCluster())
                .build(KafkaConnectApi.class);

        int drifted = 0;
        int checked = 0;

        for (File file : files) {
            Map<String, String> fileConfig;
            String connectorName;
            try {
                fileConfig = readConfig(file);
                connectorName = readName(file);
            }
            catch (IOException | IllegalArgumentException e) {
                spec.commandLine().getErr().println("Couldn't read " + file.getPath() + ": " + e.getMessage());
                return CommandLine.ExitCode.SOFTWARE;
            }

            if (connectorName == null || connectorName.isBlank()) {
                spec.commandLine().getErr().println("No connector name in " + file.getPath() + "; add a \"name\" field to the file.");
                return CommandLine.ExitCode.SOFTWARE;
            }

            Map<String, String> liveConfig;
            try {
                liveConfig = kafkaConnectApi.getConnectorConfig(connectorName);
            }
            catch (KafkaConnectNotFoundException e) {
                spec.commandLine().getErr().println("Connector " + connectorName + " is not registered on the cluster.");
                return CommandLine.ExitCode.SOFTWARE;
            }
            catch (KafkaConnectException e) {
                spec.commandLine().getErr().println("Couldn't read the configuration of " + connectorName + ": " + e.getMessage());
                return CommandLine.ExitCode.SOFTWARE;
            }

            checked++;
            ConfigDrift.Drift drift = ConfigDrift.compare(fileConfig, liveConfig);
            if (drift.isEmpty()) {
                if (!quiet) {
                    spec.commandLine().getOut().println(connectorName + ": no drift");
                }
                continue;
            }

            drifted++;
            printDrift(connectorName, drift);
        }

        spec.commandLine().getOut().println();
        spec.commandLine().getOut().println(String.format("Checked %d connector(s), %d drifted.", checked, drifted));

        return drifted > 0 ? 1 : 0;
    }

    private void printDrift(String connectorName, ConfigDrift.Drift drift) {
        spec.commandLine().getOut().println(ANSI_WHITE_BOLD + connectorName + ANSI_RESET + " (" + drift.differenceCount() + " difference(s))");

        for (Map.Entry<String, ConfigDrift.Change> change : drift.changed().entrySet()) {
            spec.commandLine().getOut().println("  ~ " + change.getKey());
            spec.commandLine().getOut().println("      file: " + change.getValue().expected());
            spec.commandLine().getOut().println("      live: " + change.getValue().actual());
        }
        for (String key : drift.added()) {
            spec.commandLine().getOut().println("  + " + key + " (only on the cluster)");
        }
        for (String key : drift.removed()) {
            spec.commandLine().getOut().println("  - " + key + " (only in the file)");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDocument(File file) throws IOException {
        if (!file.exists()) {
            throw new IOException("file does not exist");
        }
        Map<String, Object> document = mapper.readValue(Files.readString(file.toPath()), Map.class);
        if (document == null) {
            throw new IOException("file is empty");
        }
        return document;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readConfig(File file) throws IOException {
        Map<String, Object> document = readDocument(file);
        Object config = document.containsKey("config") ? document.get("config") : document;
        if (!(config instanceof Map)) {
            throw new IOException("no configuration object found");
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) config).entrySet()) {
            if ("name".equals(entry.getKey()) && !document.containsKey("config")) {
                continue;
            }
            result.put(entry.getKey(), entry.getValue() == null ? null : String.valueOf(entry.getValue()));
        }
        return result;
    }

    private String readName(File file) throws IOException {
        Map<String, Object> document = readDocument(file);
        Object name = document.get("name");
        return name == null ? null : String.valueOf(name);
    }
}
