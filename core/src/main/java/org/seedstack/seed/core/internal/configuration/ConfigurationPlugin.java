/**
 * Copyright (c) 2013-2016, The SeedStack authors <http://seedstack.org>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.seedstack.seed.core.internal.configuration;

import com.google.common.collect.Sets;
import io.nuun.kernel.api.plugin.InitState;
import io.nuun.kernel.api.plugin.context.InitContext;
import io.nuun.kernel.api.plugin.request.ClasspathScanRequest;
import io.nuun.kernel.core.AbstractPlugin;
import org.seedstack.coffig.Coffig;
import org.seedstack.coffig.provider.InMemoryProvider;
import org.seedstack.coffig.provider.JacksonProvider;
import org.seedstack.seed.Application;
import org.seedstack.seed.ApplicationConfig;
import org.seedstack.seed.SeedException;
import org.seedstack.seed.core.SeedRuntime;
import org.seedstack.seed.core.internal.CoreErrorCode;
import org.seedstack.seed.diagnostic.DiagnosticManager;
import org.seedstack.seed.spi.ApplicationProvider;
import org.seedstack.shed.ClassLoaders;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Core plugins that detects configuration files and adds them to the global configuration object.
 */
public class ConfigurationPlugin extends AbstractPlugin implements ApplicationProvider {
    public static final String EXTERNAL_CONFIG_PREFIX = "seedstack.config.";
    private static final String CONFIGURATION_PACKAGE = "META-INF.configuration";
    private static final String CONFIGURATION_LOCATION = "META-INF/configuration/";
    private static final String YAML_REGEX = ".*\\.yaml";
    private static final String YML_REGEX = ".*\\.yml";
    private static final String JSON_REGEX = ".*\\.json";
    private static final String SYSTEM_PROPERTIES_PROVIDER = "system-properties-config";
    private static final String KERNEL_PARAM_PROVIDER = "kernel-parameters-config";
    private static final String SCANNED_PROVIDER = "scanned-config";
    private static final String SCANNED_OVERRIDE_PROVIDER = "scanned-config-override";
    private SeedRuntime seedRuntime;
    private Coffig configuration;
    private DiagnosticManager diagnosticManager;
    private Application application;

    @Override
    public String name() {
        return "config";
    }

    @Override
    public void provideContainerContext(Object containerContext) {
        seedRuntime = (SeedRuntime) containerContext;
        configuration = seedRuntime.getConfiguration();
        diagnosticManager = seedRuntime.getDiagnosticManager();
    }

    @Override
    public String pluginPackageRoot() {
        Set<String> basePackages = new HashSet<>(configuration.get(ApplicationConfig.class).getBasePackages());
        basePackages.add(CONFIGURATION_PACKAGE);
        return String.join(",", basePackages);
    }

    @Override
    public Collection<ClasspathScanRequest> classpathScanRequests() {
        return classpathScanRequestBuilder()
                .resourcesRegex(YAML_REGEX)
                .resourcesRegex(YML_REGEX)
                .resourcesRegex(JSON_REGEX)
                .build();
    }

    @SuppressWarnings("unchecked")
    @Override
    public InitState init(InitContext initContext) {
        detectSystemPropertiesConfig();
        detectKernelParamConfig(initContext);
        detectConfigurationFiles(initContext);

        // we don't reuse the ApplicationConfig object as config may have changed since pluginPackageRoot()
        application = new ApplicationImpl(configuration.get(ApplicationConfig.class), configuration);
        diagnosticManager.registerDiagnosticInfoCollector("application", new ApplicationDiagnosticCollector(application));

        return InitState.INITIALIZED;
    }

    private void detectKernelParamConfig(InitContext initContext) {
        InMemoryProvider kernelParamConfigProvider = new InMemoryProvider();

        for (Map.Entry<String, String> kernelParam : initContext.kernelParams().entrySet()) {
            if (kernelParam.getKey().startsWith(EXTERNAL_CONFIG_PREFIX)) {
                addValue(kernelParamConfigProvider, kernelParam.getKey(), kernelParam.getValue());
            }
        }

        seedRuntime.registerConfigurationProvider(
                KERNEL_PARAM_PROVIDER,
                kernelParamConfigProvider,
                ConfigurationPriority.KERNEL_PARAMETERS_CONFIG
        );
    }

    private void addValue(InMemoryProvider inMemoryProvider, String key, String value) {
        if (value.contains(",")) {
            inMemoryProvider.put(key.substring(EXTERNAL_CONFIG_PREFIX.length()), Arrays.stream(value.split(",")).map(String::trim).toArray(String[]::new));
        } else {
            inMemoryProvider.put(key.substring(EXTERNAL_CONFIG_PREFIX.length()), value);
        }
    }

    private void detectSystemPropertiesConfig() {
        InMemoryProvider systemPropertiesProvider = new InMemoryProvider();

        Properties systemProperties = System.getProperties();
        for (String systemProperty : systemProperties.stringPropertyNames()) {
            if (systemProperty.startsWith(EXTERNAL_CONFIG_PREFIX)) {
                addValue(systemPropertiesProvider, systemProperty, systemProperties.getProperty(systemProperty));
            }
        }

        seedRuntime.registerConfigurationProvider(
                SYSTEM_PROPERTIES_PROVIDER,
                systemPropertiesProvider,
                ConfigurationPriority.SYSTEM_PROPERTIES_CONFIG
        );
    }

    private void detectConfigurationFiles(InitContext initContext) {
        JacksonProvider jacksonProvider = new JacksonProvider();
        JacksonProvider jacksonOverrideProvider = new JacksonProvider();

        for (String configurationResource : retrieveConfigurationResources(initContext)) {
            try {
                ClassLoader classLoader = ClassLoaders.findMostCompleteClassLoader();
                Enumeration<URL> urlEnumeration = classLoader.getResources(configurationResource);
                while (urlEnumeration.hasMoreElements()) {
                    if (isOverrideResource(configurationResource)) {
                        jacksonOverrideProvider.addSource(urlEnumeration.nextElement());
                    } else {
                        jacksonProvider.addSource(urlEnumeration.nextElement());
                    }
                }
            } catch (IOException e) {
                throw SeedException.wrap(e, CoreErrorCode.UNABLE_TO_LOAD_CONFIGURATION_RESOURCE).put("resource", configurationResource);
            }
        }

        seedRuntime.registerConfigurationProvider(
                SCANNED_PROVIDER,
                jacksonProvider,
                ConfigurationPriority.SCANNED
        );

        seedRuntime.registerConfigurationProvider(
                SCANNED_OVERRIDE_PROVIDER,
                jacksonProvider,
                ConfigurationPriority.SCANNED_OVERRIDE
        );
    }

    private Set<String> retrieveConfigurationResources(InitContext initContext) {
        Set<String> allConfigurationResources = Sets.newHashSet();
        allConfigurationResources.addAll(collectConfigResources(initContext, YAML_REGEX));
        allConfigurationResources.addAll(collectConfigResources(initContext, YML_REGEX));
        allConfigurationResources.addAll(collectConfigResources(initContext, JSON_REGEX));
        return allConfigurationResources;
    }

    private List<String> collectConfigResources(InitContext initContext, String regex) {
        return initContext.mapResourcesByRegex()
                .get(regex)
                .stream()
                .filter(propsResource -> propsResource.startsWith(CONFIGURATION_LOCATION))
                .collect(Collectors.toList());
    }

    private boolean isOverrideResource(String configurationResource) {
        return configurationResource.endsWith(".override.yaml") || configurationResource.endsWith(".override.json");
    }

    @Override
    public Object nativeUnitModule() {
        return new ConfigurationModule(application);
    }

    @Override
    public Application getApplication() {
        return application;
    }
}
