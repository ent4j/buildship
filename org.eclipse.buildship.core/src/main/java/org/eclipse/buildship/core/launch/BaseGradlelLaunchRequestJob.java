/*
 * Copyright (c) 2015 the original author or authors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Etienne Studer & Donát Csikós (Gradle Inc.) - initial API and implementation and initial documentation
 */

package org.eclipse.buildship.core.launch;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.List;

import org.gradle.tooling.ProgressListener;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import com.gradleware.tooling.toolingclient.Request;
import com.gradleware.tooling.toolingmodel.OmniBuildEnvironment;
import com.gradleware.tooling.toolingmodel.repository.FetchStrategy;
import com.gradleware.tooling.toolingmodel.repository.FixedRequestAttributes;
import com.gradleware.tooling.toolingmodel.repository.ModelRepository;
import com.gradleware.tooling.toolingmodel.repository.TransientRequestAttributes;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.ILaunch;

import org.eclipse.buildship.core.CorePlugin;
import org.eclipse.buildship.core.GradlePluginsRuntimeException;
import org.eclipse.buildship.core.console.ProcessDescription;
import org.eclipse.buildship.core.console.ProcessStreams;
import org.eclipse.buildship.core.event.Event;
import org.eclipse.buildship.core.i18n.CoreMessages;
import org.eclipse.buildship.core.launch.internal.BuildExecutionParticipants;
import org.eclipse.buildship.core.util.collections.CollectionsUtils;
import org.eclipse.buildship.core.util.file.FileUtils;
import org.eclipse.buildship.core.util.gradle.GradleDistributionFormatter;
import org.eclipse.buildship.core.util.progress.DelegatingProgressListener;
import org.eclipse.buildship.core.util.progress.ToolingApiJob;

public abstract class BaseGradlelLaunchRequestJob extends ToolingApiJob {

    // todo (etst) close streams when done

    protected abstract FixedRequestAttributes getRequestAttributes();

    protected abstract Request<Void> createRequest();

    protected abstract String getJobMainTaskName();

    protected abstract String getDisplayName();

    protected abstract Event eventBeforeExecutionStarts(Request<Void> request);

    protected abstract ILaunch getLaunch();

    protected abstract void writeAddAditionalConfigurationInfo(OutputStreamWriter writer) throws IOException;


    protected BaseGradlelLaunchRequestJob(String name, boolean notifyUserAboutBuildFailures) {
        super(name, notifyUserAboutBuildFailures);
    }

    @Override
    protected final void runToolingApiJob(IProgressMonitor monitor) {
        // activate all plugins which contribute to a build execution
        BuildExecutionParticipants.activateParticipantPlugins();

        // derive all build launch settings from the launch configuration
        FixedRequestAttributes fixedAttributes = getRequestAttributes();

        // start tracking progress
        monitor.beginTask(getJobMainTaskName(), IProgressMonitor.UNKNOWN);

        String processName = getDisplayName();
        ProcessDescription processDescription = ProcessDescription.with(processName, getLaunch(), this);
        ProcessStreams processStreams = CorePlugin.processStreamsProvider().createProcessStreams(processDescription);

        // fetch build environment
        List<ProgressListener> listeners = ImmutableList.<ProgressListener>of(new DelegatingProgressListener(monitor));
        TransientRequestAttributes transientAttributes = new TransientRequestAttributes(false, processStreams.getOutput(), processStreams.getError(), processStreams.getInput(),
                listeners, ImmutableList.<org.gradle.tooling.events.ProgressListener>of(), getToken());

        // configure the request's fixed attributes with the build launch settings derived from the
        // launch configuration
        Request<Void> request = createRequest();
        fixedAttributes.apply(request);

        // configure the request's transient attributes
        request.standardOutput(processStreams.getOutput());
        request.standardError(processStreams.getError());
        request.standardInput(processStreams.getInput());
        request.progressListeners(listeners.toArray(new ProgressListener[listeners.size()]));
        request.cancellationToken(getToken());

        // print the applied run configuration settings at the beginning of the console output
        OutputStreamWriter writer = new OutputStreamWriter(processStreams.getConfiguration());

        writeFixedRequestAttributes(monitor, fixedAttributes, transientAttributes, writer);

        // notify the listeners before executing the build launch request
        Event event = eventBeforeExecutionStarts(request);
        CorePlugin.listenerRegistry().dispatch(event);

        // launch the build
        request.executeAndWait();
    }

    private void writeFixedRequestAttributes(IProgressMonitor monitor, FixedRequestAttributes fixedAttributes, TransientRequestAttributes transientAttributes,
            OutputStreamWriter writer) {
        OmniBuildEnvironment buildEnvironment = fetchBuildEnvironment(fixedAttributes, transientAttributes, monitor);
        // should the user not specify values for the gradleUserHome and javaHome, their default
        // values will not be specified in the launch configurations
        // as such, these attributes are retrieved separately from the build environment
        File gradleUserHome = fixedAttributes.getGradleUserHome();
        if (gradleUserHome == null) {
            gradleUserHome = buildEnvironment.getGradle().getGradleUserHome().or(null);
        }
        File javaHome = fixedAttributes.getJavaHome();
        if (javaHome == null) {
            javaHome = buildEnvironment.getJava().getJavaHome();
        }
        String gradleVersion = buildEnvironment.getGradle().getGradleVersion();

        try {
            writer.write(String.format("%s: %s%n", CoreMessages.RunConfiguration_Label_WorkingDirectory, fixedAttributes.getProjectDir().getAbsolutePath()));
            writer.write(String.format("%s: %s%n", CoreMessages.RunConfiguration_Label_GradleUserHome, toNonEmpty(gradleUserHome, CoreMessages.Value_UseGradleDefault)));
            writer.write(String.format("%s: %s%n", CoreMessages.RunConfiguration_Label_GradleDistribution, GradleDistributionFormatter.toString(fixedAttributes.getGradleDistribution())));
            writer.write(String.format("%s: %s%n", CoreMessages.RunConfiguration_Label_GradleVersion, gradleVersion));
            writer.write(String.format("%s: %s%n", CoreMessages.RunConfiguration_Label_JavaHome, toNonEmpty(javaHome, CoreMessages.Value_UseGradleDefault)));
            writer.write(String.format("%s: %s%n", CoreMessages.RunConfiguration_Label_JvmArguments, toNonEmpty(fixedAttributes.getJvmArguments(), CoreMessages.Value_None)));
            writer.write(String.format("%s: %s%n", CoreMessages.RunConfiguration_Label_Arguments, toNonEmpty(fixedAttributes.getArguments(), CoreMessages.Value_None)));
            writeAddAditionalConfigurationInfo(writer);
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            String message = String.format("Cannot write run configuration description to Gradle console.");
            CorePlugin.logger().error(message, e);
            throw new GradlePluginsRuntimeException(message, e);
        }
    }

    private String toNonEmpty(File fileValue, String defaultMessage) {
        String string = FileUtils.getAbsolutePath(fileValue).orNull();
        return string != null ? string : defaultMessage;
    }

    private String toNonEmpty(List<String> stringValues, String defaultMessage) {
        String string = Strings.emptyToNull(CollectionsUtils.joinWithSpace(stringValues));
        return string != null ? string : defaultMessage;
    }

    private OmniBuildEnvironment fetchBuildEnvironment(FixedRequestAttributes fixedRequestAttributes, TransientRequestAttributes transientRequestAttributes,
            IProgressMonitor monitor) {
        monitor.beginTask("Load Gradle Build Environment", IProgressMonitor.UNKNOWN);
        try {
            ModelRepository repository = CorePlugin.modelRepositoryProvider().getModelRepository(fixedRequestAttributes);
            return repository.fetchBuildEnvironment(transientRequestAttributes, FetchStrategy.FORCE_RELOAD);
        } finally {
            monitor.done();
        }
    }
}