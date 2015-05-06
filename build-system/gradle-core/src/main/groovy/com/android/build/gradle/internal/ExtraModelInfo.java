/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.internal;

import static com.android.builder.model.AndroidProject.PROPERTY_BUILD_MODEL_ONLY;
import static com.android.builder.model.AndroidProject.PROPERTY_BUILD_MODEL_ONLY_ADVANCED;
import static com.android.builder.model.AndroidProject.PROPERTY_INVOKED_FROM_IDE;
import static com.android.ide.common.blame.output.GradleMessageRewriter.ErrorFormatMode;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.gradle.api.BaseVariant;
import com.android.build.gradle.internal.dsl.CoreBuildType;
import com.android.build.gradle.internal.dsl.CoreProductFlavor;
import com.android.build.gradle.internal.model.ArtifactMetaDataImpl;
import com.android.build.gradle.internal.model.JavaArtifactImpl;
import com.android.build.gradle.internal.model.SyncIssueImpl;
import com.android.build.gradle.internal.model.SyncIssueKey;
import com.android.build.gradle.internal.variant.DefaultSourceProviderContainer;
import com.android.builder.core.EvaluationErrorReporter;
import com.android.builder.model.AndroidArtifact;
import com.android.builder.model.ArtifactMetaData;
import com.android.builder.model.JavaArtifact;
import com.android.builder.model.SourceProvider;
import com.android.builder.model.SourceProviderContainer;
import com.android.builder.model.SyncIssue;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

import java.io.File;
import java.util.Collection;
import java.util.Map;

/**
 * For storing additional model information.
 */
public class ExtraModelInfo extends EvaluationErrorReporter {

    @NonNull
    private final Project project;

    private final ErrorFormatMode errorFormatMode;

    private final Map<SyncIssueKey, SyncIssue> syncIssues = Maps.newHashMap();

    private final Map<String, ArtifactMetaData> extraArtifactMap = Maps.newHashMap();
    private final ListMultimap<String, AndroidArtifact> extraAndroidArtifacts = ArrayListMultimap.create();
    private final ListMultimap<String, JavaArtifact> extraJavaArtifacts = ArrayListMultimap.create();

    private final ListMultimap<String, SourceProviderContainer> extraBuildTypeSourceProviders = ArrayListMultimap.create();
    private final ListMultimap<String, SourceProviderContainer> extraProductFlavorSourceProviders = ArrayListMultimap.create();
    private final ListMultimap<String, SourceProviderContainer> extraMultiFlavorSourceProviders = ArrayListMultimap.create();

    public ExtraModelInfo(@NonNull Project project) {
        super(computeModelQueryMode(project));
        this.project = project;
        errorFormatMode = computeErrorFormatMode(project);
    }

    public Map<SyncIssueKey, SyncIssue> getSyncIssues() {
        return syncIssues;
    }

    public ErrorFormatMode getErrorFormatMode() {
        return errorFormatMode;
    }

    @Override
    @NonNull
    public SyncIssue handleSyncError(@NonNull String data, int type, @NonNull String msg) {
        SyncIssue issue;
        switch (getMode()) {
            case STANDARD:
                if (!isDependencyIssue(type)) {
                    throw new GradleException(msg);
                }
                // if it's a dependency issue we don't throw right away. we'll
                // throw during build instead.
                // but we do log.
                project.getLogger().warn("WARNING: " + msg);
                issue = new SyncIssueImpl(type, SyncIssue.SEVERITY_ERROR, data, msg);
                break;
            case IDE_LEGACY:
                // compat mode for the only issue supported before the addition of SyncIssue
                // in the model.
                if (type != SyncIssue.TYPE_UNRESOLVED_DEPENDENCY) {
                    throw new GradleException(msg);
                }
                // intended fall-through
            case IDE:
                // new IDE, able to support SyncIssue.
                issue = new SyncIssueImpl(type, SyncIssue.SEVERITY_ERROR, data, msg);
                syncIssues.put(SyncIssueKey.from(issue), issue);
                break;
            default:
                throw new RuntimeException("Unknown SyncIssue type");
        }

        return issue;
    }

    private static boolean isDependencyIssue(int type) {
        switch (type) {
            case SyncIssue.TYPE_UNRESOLVED_DEPENDENCY:
            case SyncIssue.TYPE_DEPENDENCY_IS_APK:
            case SyncIssue.TYPE_DEPENDENCY_IS_APKLIB:
            case SyncIssue.TYPE_NON_JAR_LOCAL_DEP:
            case SyncIssue.TYPE_NON_JAR_PACKAGE_DEP:
            case SyncIssue.TYPE_NON_JAR_PROVIDED_DEP:
            case SyncIssue.TYPE_JAR_DEPEND_ON_AAR:
            case SyncIssue.TYPE_MISMATCH_DEP:
                return true;
        }

        return false;

    }

    public Collection<ArtifactMetaData> getExtraArtifacts() {
        return extraArtifactMap.values();
    }

    public Collection<AndroidArtifact> getExtraAndroidArtifacts(@NonNull String variantName) {
        return extraAndroidArtifacts.get(variantName);
    }

    public Collection<JavaArtifact> getExtraJavaArtifacts(@NonNull String variantName) {
        return extraJavaArtifacts.get(variantName);
    }

    public Collection<SourceProviderContainer> getExtraFlavorSourceProviders(
            @NonNull String flavorName) {
        return extraProductFlavorSourceProviders.get(flavorName);
    }

    public Collection<SourceProviderContainer> getExtraBuildTypeSourceProviders(
            @NonNull String buildTypeName) {
        return extraBuildTypeSourceProviders.get(buildTypeName);
    }

    public void registerArtifactType(@NonNull String name,
            boolean isTest,
            int artifactType) {

        if (extraArtifactMap.get(name) != null) {
            throw new IllegalArgumentException(
                    String.format("Artifact with name %1$s already registered.", name));
        }

        extraArtifactMap.put(name, new ArtifactMetaDataImpl(name, isTest, artifactType));
    }

    public void registerBuildTypeSourceProvider(@NonNull String name,
            @NonNull CoreBuildType buildType,
            @NonNull SourceProvider sourceProvider) {
        if (extraArtifactMap.get(name) == null) {
            throw new IllegalArgumentException(String.format(
                    "Artifact with name %1$s is not yet registered. Use registerArtifactType()",
                    name));
        }

        extraBuildTypeSourceProviders.put(buildType.getName(),
                new DefaultSourceProviderContainer(name, sourceProvider));

    }

    public void registerProductFlavorSourceProvider(@NonNull String name,
            @NonNull CoreProductFlavor productFlavor,
            @NonNull SourceProvider sourceProvider) {
        if (extraArtifactMap.get(name) == null) {
            throw new IllegalArgumentException(String.format(
                    "Artifact with name %1$s is not yet registered. Use registerArtifactType()",
                    name));
        }

        extraProductFlavorSourceProviders.put(productFlavor.getName(),
                new DefaultSourceProviderContainer(name, sourceProvider));

    }

    public void registerMultiFlavorSourceProvider(@NonNull String name,
            @NonNull String flavorName,
            @NonNull SourceProvider sourceProvider) {
        if (extraArtifactMap.get(name) == null) {
            throw new IllegalArgumentException(String.format(
                    "Artifact with name %1$s is not yet registered. Use registerArtifactType()",
                    name));
        }

        extraMultiFlavorSourceProviders.put(flavorName,
                new DefaultSourceProviderContainer(name, sourceProvider));
    }

    public void registerJavaArtifact(
            @NonNull String name,
            @NonNull BaseVariant variant,
            @NonNull String assembleTaskName,
            @NonNull String javaCompileTaskName,
            @NonNull Collection<File> generatedSourceFolders,
            @NonNull Iterable<String> ideSetupTaskNames,
            @NonNull Configuration configuration,
            @NonNull File classesFolder,
            @NonNull File javaResourcesFolder,
            @Nullable SourceProvider sourceProvider) {
        ArtifactMetaData artifactMetaData = extraArtifactMap.get(name);
        if (artifactMetaData == null) {
            throw new IllegalArgumentException(String.format(
                    "Artifact with name %1$s is not yet registered. Use registerArtifactType()",
                    name));
        }
        if (artifactMetaData.getType() != ArtifactMetaData.TYPE_JAVA) {
            throw new IllegalArgumentException(
                    String.format("Artifact with name %1$s is not of type JAVA", name));
        }

        JavaArtifact artifact = new JavaArtifactImpl(
                name, assembleTaskName, javaCompileTaskName, ideSetupTaskNames,
                generatedSourceFolders, classesFolder, javaResourcesFolder, null,
                new ConfigurationDependencies(configuration), sourceProvider, null);

        extraJavaArtifacts.put(variant.getName(), artifact);
    }

    /**
     * Returns whether we are just trying to build a model for the IDE instead of building. This
     * means we will attempt to resolve dependencies even if some are broken/unsupported to avoid
     * failing the import in the IDE.
     */
    private static EvaluationMode computeModelQueryMode(@NonNull Project project) {
        if (isPropertyTrue(project, PROPERTY_BUILD_MODEL_ONLY_ADVANCED)) {
            return EvaluationMode.IDE;
        }

        if (isPropertyTrue(project, PROPERTY_BUILD_MODEL_ONLY)) {
            return EvaluationMode.IDE_LEGACY;
        }

        return EvaluationMode.STANDARD;
    }

    private static ErrorFormatMode computeErrorFormatMode(@NonNull Project project) {
        if (isPropertyTrue(project, PROPERTY_INVOKED_FROM_IDE)) {
            return ErrorFormatMode.MACHINE_PARSABLE;
        } else {
            return ErrorFormatMode.HUMAN_READABLE;
        }
    }

    private static boolean isPropertyTrue(
            @NonNull Project project,
            @NonNull String propertyName) {
        if (project.hasProperty(propertyName)) {
            Object value = project.getProperties().get(propertyName);
            if (value instanceof String) {
                return Boolean.parseBoolean((String) value);
            }
        }

        return false;
    }
}
