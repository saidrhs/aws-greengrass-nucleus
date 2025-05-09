/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.amazon.aws.iot.greengrass.component.common.RecipeFormatVersion;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.EZPlugins;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.deployment.DeploymentDirectoryManager;
import com.aws.greengrass.deployment.DeploymentQueue;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.bootstrap.BootstrapManager;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorType;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.lifecyclemanager.exceptions.InputValidationException;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.mqttclient.spool.CloudMessageSpool;
import com.aws.greengrass.mqttclient.spool.SpoolMessage;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Utils;
import com.vdurmont.semver4j.Semver;
import lombok.Getter;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.config.Topic.DEFAULT_VALUE_TIMESTAMP;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.NO_OP;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_REBOOT;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.BOOTSTRAP;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ACTIVATION;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.ROLLBACK_BOOTSTRAP;
import static com.aws.greengrass.lifecyclemanager.Kernel.DEFAULT_CONFIG_YAML_FILE_WRITE;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class KernelTest {
    private static final String EXPECTED_CONFIG_OUTPUT =
            "  main:\n"
                    + "    dependencies:\n"
                    + "    - \"" + DEFAULT_NUCLEUS_COMPONENT_NAME + "\"\n"
                    + "    - \"service1\"\n"
                    + "    lifecycle: {}\n"
                    + "  service1:\n"
                    + "    dependencies: []\n"
                    + "    lifecycle:\n"
                    + "      run:\n"
                    + "        script: \"test script\"";

    @TempDir
    protected Path tempRootDir;
    private Kernel kernel;
    private Path mockFile;
    private Path tempFile;
    DeviceConfiguration deviceConfiguration;
    @Mock
    private GreengrassService service1;
    @Mock
    private GreengrassService service2;
    @Mock
    private GreengrassService service3;
    @Mock
    private GreengrassService service4;
    @BeforeEach
    void beforeEach() throws Exception{
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
        kernel = new Kernel();

        deviceConfiguration = spy(new DeviceConfiguration(kernel.getConfig(), kernel.getKernelCommandLine()));
        kernel.getContext().put(DeviceConfiguration.class, deviceConfiguration);

        mockFile = tempRootDir.resolve("mockFile");
        Files.createFile(mockFile);

        tempFile = tempRootDir.resolve("testFile");
        Files.createFile(tempFile);
    }

    @AfterEach
    void afterEach() throws IOException {
        kernel.shutdown();
        // Some tests use a faked kernel lifecycle, so the shutdown doesn't actually shut it down
        kernel.getContext().close();
    }

    @Test
    void GIVEN_kernel_and_services_WHEN_orderedDependencies_THEN_dependencies_are_returned_in_order()
            throws Exception {
        KernelLifecycle kernelLifecycle = spy(new KernelLifecycle(kernel, new KernelCommandLine(kernel), mock(
                NucleusPaths.class)));
        doNothing().when(kernelLifecycle).initConfigAndTlog();
        kernel.setKernelLifecycle(kernelLifecycle);
        kernel.parseArgs();

        GreengrassService mockMain = new GreengrassService(
                kernel.getConfig().lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "main"));
        mockMain.postInject();
        when(kernelLifecycle.getMain()).thenReturn(mockMain);

        GreengrassService service1 = new GreengrassService(
                kernel.getConfig().lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "service1"));
        service1.postInject();
        GreengrassService service2 = new GreengrassService(
                kernel.getConfig().lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "service2"));
        service2.postInject();
        GreengrassService nucleus = kernel.locate(DEFAULT_NUCLEUS_COMPONENT_NAME);

        List<GreengrassService> od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        // Nucleus component is always present as an additional dependency of main
        assertThat(od, hasSize(2));
        assertEquals(mockMain, od.get(1));

        mockMain.addOrUpdateDependency(service1, DependencyType.HARD, false);

        od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        // Nucleus component is always present as an additional dependency of main
        assertThat(od, hasSize(3));

        assertThat(od.get(0), anyOf(is(service1), is(nucleus)));
        assertThat(od.get(1), anyOf(is(service1), is(nucleus)));
        assertEquals(mockMain, od.get(2));

        mockMain.addOrUpdateDependency(service2, DependencyType.HARD, false);

        od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        // Nucleus component is always present as an additional dependency of main
        assertThat(od, hasSize(4));

        // Since service 1, 2 and Nucleus are equal in the tree, they may come back as either position 1, 2 or 3
        assertThat(od.get(0), anyOf(is(service1), is(service2), is(nucleus)));
        assertThat(od.get(1), anyOf(is(service1), is(service2), is(nucleus)));
        assertThat(od.get(2), anyOf(is(service1), is(service2), is(nucleus)));
        assertEquals(mockMain, od.get(3));

        service1.addOrUpdateDependency(service2, DependencyType.HARD, false);

        od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        // Nucleus component is always present as an additional dependency of main
        assertThat(od, hasSize(4));

        // Now that 2 is a dependency of 1, 2 has to be ordered before 1
        // Possible orders are -> [service2, service1, nucleus, main]; [nucleus, service2, service1, main]
        // and [service2, nucleus, service1, main]
        assertThat(od.get(0), anyOf(is(service2), is(nucleus)));
        assertThat(od.get(1), anyOf(is(service1), is(service2), is(nucleus)));
        assertThat(od.get(2), anyOf(is(service1), is(nucleus)));
        assertEquals(mockMain, od.get(3));
    }

    @Test
    void GIVEN_kernel_and_services_WHEN_orderedDependencies_with_a_cycle_THEN_no_dependencies_returned()
            throws InputValidationException {
        KernelLifecycle kernelLifecycle = spy(new KernelLifecycle(kernel, mock(KernelCommandLine.class),
                mock(NucleusPaths.class)));
        doNothing().when(kernelLifecycle).initConfigAndTlog();
        kernel.setKernelLifecycle(kernelLifecycle);
        kernel.parseArgs();

        GreengrassService mockMain =
                new GreengrassService(kernel.getConfig()
                        .lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "main"));
        mockMain.postInject();
        when(kernelLifecycle.getMain()).thenReturn(mockMain);

        GreengrassService service1 =
                new GreengrassService(kernel.getConfig()
                        .lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "service1"));
        service1.postInject();

        // Introduce a dependency cycle
        service1.addOrUpdateDependency(mockMain, DependencyType.HARD, true);
        mockMain.addOrUpdateDependency(service1, DependencyType.HARD, true);

        // Nucleus component is always present as an additional dependency of main
        List<GreengrassService> od = new ArrayList<>(kernel.orderedDependencies());
        assertNotNull(od);
        assertThat(od, hasSize(1));
    }

    @Test
    void GIVEN_kernel_with_services_WHEN_writeConfig_THEN_service_config_written_to_file()
            throws Exception {
        KernelLifecycle kernelLifecycle = spy(new KernelLifecycle(kernel, mock(KernelCommandLine.class),
                kernel.getNucleusPaths()));
        kernel.setKernelLifecycle(kernelLifecycle);

        GreengrassService mockMain = new GreengrassService(
                kernel.getConfig().lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "main"));
        mockMain.postInject();
        when(kernelLifecycle.getMain()).thenReturn(mockMain);
        GreengrassService service1 = new GreengrassService(
                kernel.getConfig().lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "service1"));
        service1.postInject();

        kernel.parseArgs();

        // Add dependency on service1 to main
        mockMain.addOrUpdateDependency(service1, DependencyType.HARD, false);
        ((List<String>) kernel.findServiceTopic("main").findLeafChild(
                GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC).getOnce())
                .add("service1");
        kernel.findServiceTopic("service1").lookup(GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC, "run", "script")
                .withValue("test script");

        StringWriter writer = new StringWriter();
        kernel.writeConfig(writer);
        assertThat(writer.toString(), containsString(EXPECTED_CONFIG_OUTPUT));

        kernel.writeEffectiveConfig();
        String readFile =
                new String(Files.readAllBytes(kernel.getNucleusPaths().configPath()
                        .resolve(DEFAULT_CONFIG_YAML_FILE_WRITE)),
                StandardCharsets.UTF_8);
        assertThat(readFile, containsString(EXPECTED_CONFIG_OUTPUT));
    }

    @Test
    void GIVEN_kernel_WHEN_locate_finds_definition_in_config_THEN_create_GenericExternalService()
            throws Exception {
        Configuration config = kernel.getConfig();
        config.lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "1",
                GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC);

        GreengrassService main = kernel.locate("1");
        assertEquals("1", main.getName());
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_WHEN_locate_finds_class_definition_in_config_THEN_create_service(ExtensionContext context)
            throws Exception {
        // We need to launch the kernel here as this triggers EZPlugins to search the classpath for @ImplementsService
        // it complains that there's no main, but we don't care for this test
        ignoreExceptionUltimateCauseWithMessage(context, "No matching definition in system model for: main");
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }
        Configuration config = kernel.getConfig();
        config.lookup(GreengrassService.SERVICES_NAMESPACE_TOPIC, "1", "class")
                .withValue(TestClass.class.getName());

        GreengrassService main = kernel.locate("1");
        assertEquals("tester", main.getName());

        kernel.getContext().get(EZPlugins.class).scanSelfClasspath();
        GreengrassService service2 = kernel.locate("testImpl");
        assertEquals("testImpl", service2.getName());
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_with_disk_spooler_config_WHEN_locate_spooler_impl_THEN_create_test_spooler_service()
            throws Exception {
        try {
            System.setProperty("aws.greengrass.scanSelfClasspath", "true");
            try {
                kernel.parseArgs("-i", getClass().getResource("spooler_config.yaml").toString()).launch();
            } catch (RuntimeException ignored) {
            }
            GreengrassService service = kernel.locate("testSpooler");
            assertEquals("testSpooler", service.getName());
            assertTrue(service instanceof CloudMessageSpool);
        } finally {
            System.setProperty("aws.greengrass.scanSelfClasspath", "false");
        }
    }

    @Test
    void GIVEN_kernel_WHEN_locate_finds_classname_but_not_class_THEN_throws_ServiceLoadException() {
        String badClassName = TestClass.class.getName()+"lkajsdklajglsdj";

        Configuration config = kernel.getConfig();
        config.lookup(GreengrassService.SERVICES_NAMESPACE_TOPIC, "2", "class")
                .withValue(badClassName);

        ServiceLoadException ex = assertThrows(ServiceLoadException.class, () -> kernel.locate("2"));
        assertEquals("Can't load service class from " + badClassName, ex.getMessage());
    }

    @Test
    void GIVEN_kernel_WHEN_locate_finds_no_definition_in_config_THEN_throws_ServiceLoadException() {
        ServiceLoadException ex = assertThrows(ServiceLoadException.class, () -> kernel.locate("5"));
        assertEquals("No matching definition in system model for: 5", ex.getMessage());
    }

    @Test
    void GIVEN_kernel_with_services_WHEN_get_root_package_with_version_THEN_kernel_returns_info() {

        GreengrassService service1 = new GreengrassService(kernel.getConfig()
                        .lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "service1"));
        GreengrassService service2 = new GreengrassService(kernel.getConfig()
                .lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, "service2"));
        service1.getConfig().lookup(VERSION_CONFIG_KEY).dflt("1.0.0");
        service2.getConfig().lookup(VERSION_CONFIG_KEY).dflt("1.1.0");

        GreengrassService mockMain = mock(GreengrassService.class);
        Map<GreengrassService, DependencyType> mainsDependency = new HashMap<>();
        mainsDependency.put(service1, null);
        mainsDependency.put(service2, null);
        when(mockMain.getDependencies()).thenReturn(mainsDependency);

        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        when(kernelLifecycle.getMain()).thenReturn(mockMain);
        kernel.setKernelLifecycle(kernelLifecycle);

        Map<String, String> rootPackageNameAndVersion = kernel.getRunningCustomRootComponents();
        assertEquals(2, rootPackageNameAndVersion.size());
        assertEquals("1.0.0", rootPackageNameAndVersion.get("service1"));
        assertEquals("1.1.0", rootPackageNameAndVersion.get("service2"));

    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_launch_WHEN_deployment_activation_happy_path_THEN_inject_deployment()
            throws Exception {
        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        doNothing().when(kernelLifecycle).launch();
        doNothing().when(kernelLifecycle).initConfigAndTlog(any());
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(KERNEL_ACTIVATION).when(kernelAlternatives).determineDeploymentStage(any(), any());
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
        kernel.getContext().put(KernelAlternatives.class, kernelAlternatives);

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Deployment.class)).when(deploymentDirectoryManager).readDeploymentMetadata();
        doReturn(mockFile).when(deploymentDirectoryManager).getTargetConfigFilePath();
        doReturn(deploymentDirectoryManager).when(kernelCommandLine).getDeploymentDirectoryManager();

        doReturn(mock(BootstrapManager.class)).when(kernelCommandLine).getBootstrapManager();

        kernel.setKernelCommandLine(kernelCommandLine);
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }

        DeploymentQueue deployments = kernel.getContext().get(DeploymentQueue.class);
        assertThat(deployments.toArray(), hasSize(1));
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_launch_WHEN_deployment_rollback_cannot_reload_deployment_THEN_proceed_as_default(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, IOException.class);

        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        doNothing().when(kernelLifecycle).initConfigAndTlog(any());
        doNothing().when(kernelLifecycle).launch();
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(KERNEL_ROLLBACK).when(kernelAlternatives).determineDeploymentStage(any(), any());
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
        kernel.getContext().put(KernelAlternatives.class, kernelAlternatives);

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doThrow(new IOException()).when(deploymentDirectoryManager).readDeploymentMetadata();
        doReturn(mockFile).when(deploymentDirectoryManager).getSnapshotFilePath();
        doReturn(deploymentDirectoryManager).when(kernelCommandLine).getDeploymentDirectoryManager();

        doReturn(mock(BootstrapManager.class)).when(kernelCommandLine).getBootstrapManager();

        kernel.setKernelCommandLine(kernelCommandLine);
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }

        DeploymentQueue deployments = kernel.getContext().get(DeploymentQueue.class);
        assertNull(deployments.poll());
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_launch_WHEN_bootstrap_task_finishes_THEN_prepare_restart_into_activation()
            throws Exception {
        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(BOOTSTRAP).when(kernelAlternatives).determineDeploymentStage(any(), any());
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
        kernel.getContext().put(KernelAlternatives.class, kernelAlternatives);

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Path.class)).when(deploymentDirectoryManager).getBootstrapTaskFilePath();
        doReturn(mockFile).when(deploymentDirectoryManager).getTargetConfigFilePath();
        doReturn(deploymentDirectoryManager).when(kernelCommandLine).getDeploymentDirectoryManager();

        BootstrapManager bootstrapManager = mock(BootstrapManager.class);
        doReturn(NO_OP).when(bootstrapManager).executeAllBootstrapTasksSequentially(any());
        doReturn(false).when(bootstrapManager).hasNext();
        doReturn(bootstrapManager).when(kernelCommandLine).getBootstrapManager();

        kernel.setKernelCommandLine(kernelCommandLine);
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }

        verify(kernelLifecycle).shutdown(eq(30), eq(REQUEST_RESTART));
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_launch_WHEN_bootstrap_task_requires_reboot_THEN_prepare_reboot()
            throws Exception {
        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(BOOTSTRAP).when(kernelAlternatives).determineDeploymentStage(any(), any());
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
        kernel.getContext().put(KernelAlternatives.class, kernelAlternatives);

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Path.class)).when(deploymentDirectoryManager).getBootstrapTaskFilePath();
        doReturn(mockFile).when(deploymentDirectoryManager).getTargetConfigFilePath();
        doReturn(deploymentDirectoryManager).when(kernelCommandLine).getDeploymentDirectoryManager();

        BootstrapManager bootstrapManager = mock(BootstrapManager.class);
        doReturn(REQUEST_REBOOT).when(bootstrapManager).executeAllBootstrapTasksSequentially(any());
        doReturn(true).when(bootstrapManager).hasNext();
        doReturn(bootstrapManager).when(kernelCommandLine).getBootstrapManager();

        kernel.setKernelCommandLine(kernelCommandLine);
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }

        verify(kernelLifecycle).shutdown(eq(30), eq(REQUEST_REBOOT));
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_launch_WHEN_bootstrap_task_fails_THEN_prepare_rollback(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, ServiceUpdateException.class);

        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(BOOTSTRAP).when(kernelAlternatives).determineDeploymentStage(any(), any());
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
        kernel.getContext().put(KernelAlternatives.class, kernelAlternatives);

        Deployment deployment = mock(Deployment.class);

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Path.class)).when(deploymentDirectoryManager).getBootstrapTaskFilePath();
        doReturn(mockFile).when(deploymentDirectoryManager).getTargetConfigFilePath();
        doReturn(deployment).when(deploymentDirectoryManager).readDeploymentMetadata();
        doReturn(deploymentDirectoryManager).when(kernelCommandLine).getDeploymentDirectoryManager();

        ServiceUpdateException mockSUE = new ServiceUpdateException("mock error", DeploymentErrorCode.COMPONENT_BOOTSTRAP_ERROR,
                DeploymentErrorType.USER_COMPONENT_ERROR);
        BootstrapManager bootstrapManager = mock(BootstrapManager.class);
        doThrow(mockSUE).when(bootstrapManager).executeAllBootstrapTasksSequentially(any());
        doReturn(bootstrapManager).when(kernelCommandLine).getBootstrapManager();

        kernel.setKernelCommandLine(kernelCommandLine);
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }

        verify(kernelAlternatives).prepareRollback();
        verify(deployment).setErrorStack(eq(Arrays.asList("DEPLOYMENT_FAILURE", "COMPONENT_UPDATE_ERROR",
                "COMPONENT_BOOTSTRAP_ERROR")));
        verify(deployment).setErrorTypes(eq(Collections.singletonList("USER_COMPONENT_ERROR")));
        verify(deploymentDirectoryManager).writeDeploymentMetadata(eq(deployment));
        verify(kernelLifecycle).shutdown(eq(30), eq(REQUEST_RESTART));
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_launch_WHEN_bootstrap_task_fails_and_prepare_rollback_fails_THEN_continue(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, ServiceUpdateException.class);
        ignoreExceptionOfType(context, IOException.class);

        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(BOOTSTRAP).when(kernelAlternatives).determineDeploymentStage(any(), any());
        doThrow(new IOException()).when(kernelAlternatives).prepareRollback();
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
        kernel.getContext().put(KernelAlternatives.class, kernelAlternatives);

        Deployment deployment = mock(Deployment.class);
        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Path.class)).when(deploymentDirectoryManager).getBootstrapTaskFilePath();
        doReturn(mockFile).when(deploymentDirectoryManager).getTargetConfigFilePath();
        doReturn(deployment).when(deploymentDirectoryManager).readDeploymentMetadata();
        doNothing().when(deploymentDirectoryManager).writeDeploymentMetadata(any());
        doReturn(deploymentDirectoryManager).when(kernelCommandLine).getDeploymentDirectoryManager();

        BootstrapManager bootstrapManager = mock(BootstrapManager.class);
        doThrow(new ServiceUpdateException("mock error")).when(bootstrapManager).executeAllBootstrapTasksSequentially(any());
        doReturn(bootstrapManager).when(kernelCommandLine).getBootstrapManager();

        kernel.setKernelCommandLine(kernelCommandLine);
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }

        verify(kernelAlternatives).prepareRollback();
        verify(kernelLifecycle).launch();
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_launch_WHEN_rollback_bootstrap_finishes_THEN_prepare_restart_into_kernel_rollback()
            throws Exception {
        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(ROLLBACK_BOOTSTRAP).when(kernelAlternatives).determineDeploymentStage(any(), any());
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
        kernel.getContext().put(KernelAlternatives.class, kernelAlternatives);

        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Path.class)).when(deploymentDirectoryManager).getRollbackBootstrapTaskFilePath();
        doReturn(mockFile).when(deploymentDirectoryManager).getSnapshotFilePath();
        doReturn(deploymentDirectoryManager).when(kernelCommandLine).getDeploymentDirectoryManager();

        BootstrapManager bootstrapManager = mock(BootstrapManager.class);
        doReturn(NO_OP).when(bootstrapManager).executeAllBootstrapTasksSequentially(any());
        doReturn(false).when(bootstrapManager).hasNext();
        doReturn(bootstrapManager).when(kernelCommandLine).getBootstrapManager();

        kernel.setKernelCommandLine(kernelCommandLine);
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }

        verify(kernelLifecycle).shutdown(eq(30), eq(REQUEST_RESTART));
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Test
    void GIVEN_kernel_launch_WHEN_rollback_bootstrap_fails_THEN_inject_deployment(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, ServiceUpdateException.class);
        ignoreExceptionOfType(context, IOException.class);

        KernelLifecycle kernelLifecycle = mock(KernelLifecycle.class);
        doNothing().when(kernelLifecycle).initConfigAndTlog(any());
        doNothing().when(kernelLifecycle).launch();
        kernel.setKernelLifecycle(kernelLifecycle);

        KernelCommandLine kernelCommandLine = mock(KernelCommandLine.class);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(ROLLBACK_BOOTSTRAP).when(kernelAlternatives).determineDeploymentStage(any(), any());
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
        kernel.getContext().put(KernelAlternatives.class, kernelAlternatives);

        Deployment deployment = mock(Deployment.class);
        DeploymentDirectoryManager deploymentDirectoryManager = mock(DeploymentDirectoryManager.class);
        doReturn(mock(Path.class)).when(deploymentDirectoryManager).getRollbackBootstrapTaskFilePath();
        doReturn(mockFile).when(deploymentDirectoryManager).getSnapshotFilePath();
        doReturn(deployment).when(deploymentDirectoryManager).readDeploymentMetadata();
        doReturn(deploymentDirectoryManager).when(kernelCommandLine).getDeploymentDirectoryManager();

        BootstrapManager bootstrapManager = mock(BootstrapManager.class);
        doThrow(new ServiceUpdateException("mock error")).when(bootstrapManager).executeAllBootstrapTasksSequentially(any());
        doReturn(bootstrapManager).when(kernelCommandLine).getBootstrapManager();

        kernel.setKernelCommandLine(kernelCommandLine);
        try {
            kernel.parseArgs().launch();
        } catch (RuntimeException ignored) {
        }

        verify(deployment).setDeploymentStage(eq(ROLLBACK_BOOTSTRAP));
        DeploymentQueue deployments = kernel.getContext().get(DeploymentQueue.class);
        assertThat(deployments.toArray(), hasSize(1));
    }

    @Test
    void GIVEN_no_launch_param_file_WHEN_persistInitialLaunchParams_THEN_write_jvm_args_to_file() throws Exception {
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        Path noFile = tempRootDir.resolve("noFile");
        doReturn(noFile).when(kernelAlternatives).getLaunchParamsPath();

        kernel.persistInitialLaunchParams(kernelAlternatives, deviceConfiguration.getNucleusComponentName());
        verify(kernelAlternatives).writeLaunchParamsToFile(anyString());
    }

    @Test
    void GIVEN_existing_launch_param_file_WHEN_persistInitialLaunchParams_THEN_skip() throws Exception {
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();

        kernel.persistInitialLaunchParams(kernelAlternatives, deviceConfiguration.getNucleusComponentName());
        verify(kernelAlternatives, times(0)).writeLaunchParamsToFile(anyString());
    }

    @Test
    void GIVEN_recipe_WHEN_initializeNucleusLifecycleConfig_THEN_init_lifecycle_and_dependencies() {
        String lifecycle = "lifecycle";
        String step = "step";
        Object interpolatedLifecycle = new HashMap<String, String>() {{
            put(lifecycle, step);
        }};
        ComponentRecipe componentRecipe = ComponentRecipe.builder()
                .lifecycle((Map<String, Object>) interpolatedLifecycle).build();

        kernel.initializeNucleusLifecycleConfig(deviceConfiguration.getNucleusComponentName(), componentRecipe);

        Topics nucleusLifecycle = kernel.getConfig().lookupTopics(DEFAULT_VALUE_TIMESTAMP,
                GreengrassService.SERVICES_NAMESPACE_TOPIC, deviceConfiguration.getNucleusComponentName(),
                GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC);
        assertEquals(nucleusLifecycle.children.size(), 1);
        String value = Coerce.toString(nucleusLifecycle.find(lifecycle));
        assertEquals(value, step);
    }

    @Test
    void GIVEN_version_WHEN_initializeNucleusVersion_THEN_init_component_version_and_env_var() {
        String nucleusComponentVersion = "test-version";
        kernel.initializeNucleusVersion(deviceConfiguration.getNucleusComponentName(), nucleusComponentVersion);

        String versionTopic = Coerce.toString(kernel.getConfig().lookup(GreengrassService.SERVICES_NAMESPACE_TOPIC,
                deviceConfiguration.getNucleusComponentName(), VERSION_CONFIG_KEY));
        assertEquals(nucleusComponentVersion, versionTopic);
        String envTopic = Coerce.toString(kernel.getConfig().lookup(GreengrassService.SETENV_CONFIG_NAMESPACE,
                Kernel.GGC_VERSION_ENV));
        assertEquals(nucleusComponentVersion, envTopic);
    }

    @Test
    void GIVEN_component_store_already_setup_WHEN_initializeComponentStore_THEN_do_nothing(
            @Mock ComponentStore componentStore, @Mock NucleusPaths nucleusPaths) throws Exception{
        Semver nucleusComponentVersion = new Semver("1.0.0");
        Path recipePath = tempRootDir.resolve("mockRecipe");
        Files.createFile(recipePath);
        doReturn(recipePath).when(componentStore).resolveRecipePath(any());
        doReturn(tempRootDir).when(nucleusPaths).unarchiveArtifactPath(any(), anyString());
        kernel.getContext().put(ComponentStore.class, componentStore);
        kernel.getContext().put(NucleusPaths.class, nucleusPaths);

        Kernel spyKernel = spy(kernel);
        kernel.initializeComponentStore(mock(KernelAlternatives.class),
                deviceConfiguration.getNucleusComponentName(), nucleusComponentVersion, recipePath, tempRootDir);

        verify(spyKernel, times(0)).copyUnpackedNucleusArtifacts(any(), any());
        verify(componentStore, times(0)).savePackageRecipe(any(), any());
    }

    @Test
    void GIVEN_component_store_not_setup_WHEN_initializeComponentStore_THEN_copy_to_component_store(
            @TempDir Path dstRecipeDir, @TempDir Path dstArtifactsDir, @TempDir Path unpackDir,
            @Mock ComponentStore componentStore, @Mock NucleusPaths nucleusPaths) throws Exception {
        Semver nucleusComponentVersion = new Semver("1.0.0");
        Path dstRecipePath = dstRecipeDir.resolve("recipe.yaml");
        doReturn(dstRecipePath).when(componentStore).resolveRecipePath(any());
        doReturn(dstArtifactsDir).when(nucleusPaths).unarchiveArtifactPath(any(), anyString());
        kernel.getContext().put(ComponentStore.class, componentStore);
        kernel.getContext().put(NucleusPaths.class, nucleusPaths);

        MockNucleusUnpackDir mockNucleusUnpackDir = new MockNucleusUnpackDir(unpackDir);
        String mockRecipeContent = getRecipeSerializer().writeValueAsString(
                com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.builder()
                        .componentName(deviceConfiguration.getNucleusComponentName()).componentVersion(nucleusComponentVersion)
                        .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020).build());
        mockNucleusUnpackDir.setup(mockRecipeContent);

        kernel.initializeComponentStore(mock(KernelAlternatives.class), deviceConfiguration.getNucleusComponentName(),
                nucleusComponentVersion, mockNucleusUnpackDir.getConfRecipe(), unpackDir);

        ComponentIdentifier componentIdentifier = new ComponentIdentifier(deviceConfiguration.getNucleusComponentName(),
                nucleusComponentVersion);

        verify(componentStore).savePackageRecipe(eq(componentIdentifier), eq(mockRecipeContent));
        mockNucleusUnpackDir.assertDirectoryEquals(dstArtifactsDir);
    }

    @Test
    void GIVEN_unpack_dir_is_nucleus_root_WHEN_initializeComponentStore_THEN_copy_to_component_store(
            @TempDir Path unpackDir, @Mock ComponentStore componentStore) throws Exception {
        Semver nucleusComponentVersion = new Semver("1.0.0");

        // Set up Nucleus root
        NucleusPaths nucleusPaths = new NucleusPaths("mock_loader_logs.log");
        nucleusPaths.setRootPath(unpackDir);
        nucleusPaths.initPaths(unpackDir, unpackDir.resolve("work"), unpackDir.resolve("packages"),
                unpackDir.resolve("config"), unpackDir.resolve("alts"), unpackDir.resolve("deployments"),
                unpackDir.resolve("cli_ipc_info"), unpackDir.resolve("bin"));
        Files.createFile(nucleusPaths.binPath().resolve("greengrass-cli"));
        Files.createFile(nucleusPaths.recipePath().resolve("someRecipe.yaml"));

        Path actualRecipe = nucleusPaths.recipePath().resolve("nucleusRecipe.yaml");
        doReturn(actualRecipe).when(componentStore).resolveRecipePath(any());
        kernel.getContext().put(ComponentStore.class, componentStore);
        kernel.getContext().put(NucleusPaths.class, nucleusPaths);

        // Set up unpack dir in Nucleus root
        MockNucleusUnpackDir mockNucleusUnpackDir = new MockNucleusUnpackDir(unpackDir);
        String mockRecipeContent = getRecipeSerializer().writeValueAsString(
                com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.builder()
                        .componentName(deviceConfiguration.getNucleusComponentName())
                        .componentVersion(nucleusComponentVersion)
                        .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020).build());
        mockNucleusUnpackDir.setup(mockRecipeContent);

        // Should only copy artifact files to component store
        kernel.initializeComponentStore(mock(KernelAlternatives.class), deviceConfiguration.getNucleusComponentName(),
                nucleusComponentVersion, mockNucleusUnpackDir.getConfRecipe(), unpackDir);

        ComponentIdentifier componentIdentifier = new ComponentIdentifier(deviceConfiguration.getNucleusComponentName(),
                nucleusComponentVersion);
        verify(componentStore).savePackageRecipe(eq(componentIdentifier), eq(mockRecipeContent));
        mockNucleusUnpackDir.assertDirectoryEquals(nucleusPaths.unarchiveArtifactPath(
                componentIdentifier, DEFAULT_NUCLEUS_COMPONENT_NAME.toLowerCase()));
    }
    @Test
    void GIVEN_all_services_autoStartable_THEN_all_services_in_services_to_track() {
        kernel = spy(kernel);
        // Arrange
        when(service1.shouldAutoStart()).thenReturn(true);
        when(service2.shouldAutoStart()).thenReturn(true);
        Set<GreengrassService> services = new HashSet<>(Arrays.asList(service1, service2));

        when(kernel.orderedDependencies()).thenReturn(Arrays.asList(service1, service2));

        // Act
        Set<GreengrassService> result = kernel.findAutoStartableServicesToTrack();

        // Assert
        assertEquals(services, result);
        assertEquals(2, result.size());
    }

    @Test
    void GIVEN_no_autoStartableServices_THEN_services_to_track_list_empty() {
        kernel = spy(kernel);
        // Arrange
        when(service1.shouldAutoStart()).thenReturn(false);
        when(service2.shouldAutoStart()).thenReturn(false);
        Set<GreengrassService> services = new HashSet<>(Arrays.asList(service1, service2));

        when(kernel.orderedDependencies()).thenReturn(services);
        when(service1.getHardDependers()).thenReturn(Collections.emptyList());
        when(service2.getHardDependers()).thenReturn(Collections.emptyList());

        // Act
        Set<GreengrassService> result = kernel.findAutoStartableServicesToTrack();

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void GIVEN_mixed_autoStartableServices_THEN_services_to_track_only_contain_autoStartableServices() {
        kernel = spy(kernel);
        // Arrange
        when(service1.shouldAutoStart()).thenReturn(true);
        when(service2.shouldAutoStart()).thenReturn(false);
        when(service3.shouldAutoStart()).thenReturn(true);
        when(service4.shouldAutoStart()).thenReturn(true);

        Set<GreengrassService> services = new HashSet<>(Arrays.asList(service1, service2, service3, service4));

        // service3 depends on service2 (non-auto-startable)
        when(service2.getHardDependers()).thenReturn(Arrays.asList(service3));
        when(service3.getHardDependers()).thenReturn(Collections.emptyList());

        when(kernel.orderedDependencies()).thenReturn(services);

        // Act
        Set<GreengrassService> result = kernel.findAutoStartableServicesToTrack();

        // Assert
        assertEquals(2, result.size());
        assertTrue(result.contains(service1));
        assertTrue(result.contains(service4));
        assertFalse(result.contains(service2));
        assertFalse(result.contains(service3));
    }

    @Test
    void GIVEN_chained_services_THEN_services_to_track_only_contain_autoStartableServices() {
        kernel = spy(kernel);
        // Arrange
        when(service1.shouldAutoStart()).thenReturn(false);
        when(service2.shouldAutoStart()).thenReturn(true);
        when(service3.shouldAutoStart()).thenReturn(true);

        Set<GreengrassService> services = new HashSet<>(Arrays.asList(service1, service2, service3));

        // service2 depends on service1, service3 depends on service2
        when(service1.getHardDependers()).thenReturn(Arrays.asList(service2));
        when(service2.getHardDependers()).thenReturn(Arrays.asList(service3));
        when(service3.getHardDependers()).thenReturn(Collections.emptyList());

        when(kernel.orderedDependencies()).thenReturn(services);

        // Act
        Set<GreengrassService> result = kernel.findAutoStartableServicesToTrack();

        // Assert
        assertTrue(result.isEmpty());
    }
    static class TestClass extends GreengrassService {
        public TestClass(Topics topics) {
            super(topics);
        }

        @Override
        public String getName() {
            return "tester";
        }
    }

    @ImplementsService(name = "testImpl")
    static class TestImplementor extends GreengrassService {
        public TestImplementor(Topics topics) {
            super(topics);
        }

        @Override
        public String getName() {
            return "testImpl";
        }
    }

    @ImplementsService(name = "testSpooler")
    static class TestSpooler extends PluginService implements CloudMessageSpool {
        public TestSpooler(Topics topics) {
            super(topics);
        }

        @Override
        public String getName() {
            return "testSpooler";
        }

        @Override
        public SpoolMessage getMessageById(long id) {
            return null;
        }

        @Override
        public void removeMessageById(long id) {

        }

        @Override
        public void add(long id, SpoolMessage message) throws IOException {

        }

        @Override
        public Iterable<Long> getAllMessageIds() throws IOException {
            return null;
        }

        @Override
        public void initializeSpooler() throws IOException {

        }
    }

    static class MockNucleusUnpackDir {
        Path root;
        Path conf;
        @Getter
        Path bin;
        Path lib;
        Path license;
        @Getter
        Path confRecipe;
        Path libJar;
        Path binLoader;
        Path binTemplate;

        public MockNucleusUnpackDir(Path root) {
            this.root = root;
            this.conf = root.resolve("conf");
            this.bin = root.resolve("bin");
            this.lib = root.resolve("lib");
            this.license = root.resolve("LICENSE");
            this.confRecipe = conf.resolve("recipe.yaml");
            this.libJar = lib.resolve("Greengrass.jar");
            this.binLoader = bin.resolve("loader");
            this.binTemplate = bin.resolve("greengrass.service.template");
        }

        public void setup(String recipeContent) throws IOException {
            Utils.createPaths(conf);
            Utils.createPaths(bin);
            Utils.createPaths(lib);

            for (Path file : Arrays.asList(license, libJar, binLoader, binTemplate)) {
                try (BufferedWriter writer = Files.newBufferedWriter(file)) {
                    writer.append(file.getFileName().toString());
                }
            }
            FileUtils.writeStringToFile(confRecipe.toFile(), recipeContent);
        }

        public void assertDirectoryEquals(Path actual) {
            assertThat(Arrays.asList(actual.toFile().list()), containsInAnyOrder("conf", "bin", "lib", "LICENSE"));
            assertThat(Arrays.asList(actual.resolve("bin").toFile().list()), containsInAnyOrder(
                    "loader", "greengrass.service.template"));
            assertThat(Arrays.asList(actual.resolve("conf").toFile().list()), containsInAnyOrder("recipe.yaml"));
            assertThat(Arrays.asList(actual.resolve("lib").toFile().list()), containsInAnyOrder("Greengrass.jar"));
        }
    }
}
