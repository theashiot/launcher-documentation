/*
 * Copyright 2015 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.swarm.arquillian.adapter;

import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.resolver.api.maven.ConfigurableMavenResolverSystem;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.jboss.shrinkwrap.resolver.api.maven.MavenResolvedArtifact;
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinate;
import org.jboss.shrinkwrap.resolver.api.maven.repository.MavenChecksumPolicy;
import org.jboss.shrinkwrap.resolver.api.maven.repository.MavenRemoteRepositories;
import org.jboss.shrinkwrap.resolver.api.maven.repository.MavenRemoteRepository;
import org.jboss.shrinkwrap.resolver.api.maven.repository.MavenUpdatePolicy;
import org.wildfly.swarm.arquillian.daemon.DaemonServiceActivator;
import org.wildfly.swarm.container.JARArchive;
import org.wildfly.swarm.msc.ServiceActivatorArchive;
import org.wildfly.swarm.tools.BuildTool;
import org.wildfly.swarm.tools.exec.SwarmExecutor;
import org.wildfly.swarm.tools.exec.SwarmProcess;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Toby Crawley
 */
public class UberjarSimpleContainer implements SimpleContainer {
    public UberjarSimpleContainer(Class<?> testClass) {
        this.testClass = testClass;
    }

    @Override
    public UberjarSimpleContainer requestedMavenArtifacts(Set<String> artifacts) {
        this.requestedMavenArtifacts = artifacts;

        return this;
    }

    @Override
    public void start(Archive<?> archive) throws Exception {
  /*
        System.err.println( ">>> CORE" );
        System.err.println(" NAME: " + archive.getName());
        for (Map.Entry<ArchivePath, Node> each : archive.getContent().entrySet()) {
            System.err.println("-> " + each.getKey());
        }
        System.err.println( "<<< CORE" );
        */

        //System.err.println("is factory: " + isContainerFactory(this.testClass));
        if (isContainerFactory(this.testClass)) {
            archive.as(JavaArchive.class)
                    .addAsServiceProvider("org.wildfly.swarm.ContainerFactory",
                                          this.testClass.getName())
                    .addClass(this.testClass)
                    .as(JARArchive.class)
                    .addModule("org.wildfly.swarm.container")
                    .addModule("org.wildfly.swarm.configuration");
        }
        archive.as(ServiceActivatorArchive.class)
                .addServiceActivator(DaemonServiceActivator.class);
        archive.as(JARArchive.class)
                .addModule("org.wildfly.swarm.arquillian.daemon")
                .addModule("org.jboss.msc");

        BuildTool tool = new BuildTool()
                .projectArchive(archive)
                .bundleDependencies(false);

        final String additionalModules = System.getProperty("swarm.build.modules");
        if (additionalModules != null) {
            tool.additionalModules(Stream.of(additionalModules.split(":"))
                                          .map(m -> new File(m).getAbsolutePath())
                                           .collect(Collectors.toList()));
        }

        MavenRemoteRepository jbossPublic =
                MavenRemoteRepositories.createRemoteRepository("jboss-public-repository-group",
                                                               "http://repository.jboss.org/nexus/content/groups/public/",
                                                               "default");
        jbossPublic.setChecksumPolicy(MavenChecksumPolicy.CHECKSUM_POLICY_IGNORE);
        jbossPublic.setUpdatePolicy(MavenUpdatePolicy.UPDATE_POLICY_NEVER);


        final ConfigurableMavenResolverSystem resolver = Maven.configureResolver()
                .withMavenCentralRepo(true)
                .withRemoteRepo(jbossPublic);

        final String additionalRepos = System.getProperty("swarm.build.repos");
        if (additionalRepos != null) {
            Arrays.asList(additionalRepos.split(","))
                    .forEach(r -> {
                        MavenRemoteRepository repo =
                                MavenRemoteRepositories.createRemoteRepository(r, r, "default");
                        repo.setChecksumPolicy(MavenChecksumPolicy.CHECKSUM_POLICY_IGNORE);
                        repo.setUpdatePolicy(MavenUpdatePolicy.UPDATE_POLICY_NEVER);
                        resolver.withRemoteRepo(repo);
                    });
        }

        final ShrinkwrapArtifactResolvingHelper resolvingHelper = new ShrinkwrapArtifactResolvingHelper(resolver);
        tool.artifactResolvingHelper(resolvingHelper);

        boolean hasRequestedArtifacts = this.requestedMavenArtifacts != null && this.requestedMavenArtifacts.size() > 0;

        if (!hasRequestedArtifacts) {
            final MavenResolvedArtifact[] deps =
                    resolvingHelper.withResolver(r -> r.loadPomFromFile("pom.xml")
                            .importRuntimeAndTestDependencies()
                            .resolve()
                            .withTransitivity()
                            .asResolvedArtifact());

            for (MavenResolvedArtifact dep : deps) {
                MavenCoordinate coord = dep.getCoordinate();
                tool.dependency(dep.getScope().name(), coord.getGroupId(),
                                coord.getArtifactId(), coord.getVersion(),
                                coord.getPackaging().getExtension(), coord.getClassifier(), dep.asFile());
            }
        } else {
            // ensure that arq daemon is available
            this.requestedMavenArtifacts.add("org.wildfly.swarm:wildfly-swarm-arquillian-daemon");
            for (String requestedDep : this.requestedMavenArtifacts) {
                final MavenResolvedArtifact[] deps =
                        resolvingHelper.withResolver(r -> r.loadPomFromFile("pom.xml")
                                .resolve(requestedDep)
                                .withTransitivity()
                                .asResolvedArtifact());

                for (MavenResolvedArtifact dep : deps) {
                    MavenCoordinate coord = dep.getCoordinate();
                    tool.dependency(dep.getScope().name(), coord.getGroupId(),
                                    coord.getArtifactId(), coord.getVersion(),
                                    coord.getPackaging().getExtension(), coord.getClassifier(), dep.asFile());
                }
            }
        }

        SwarmExecutor executor = new SwarmExecutor();
        executor.withDefaultSystemProperties();

        final String debug = System.getProperty("swarm.arquillian.debug");
        if (debug != null &&
                !"false".equals(debug)) {
            int port = 8787;
            try {
                port = Integer.parseInt(debug);
            } catch (NumberFormatException ignored) {}

            executor.withDebug(port);
        }

        Archive<?> wrapped = tool.build();

        final String dump = System.getProperty("swarm.export.swarmjar");
        if (dump != null &&
                !"false".equals(dump)) {
            final File out = new File(wrapped.getName());
            System.err.println("Dumping swarmjar to " + out.getAbsolutePath());
            wrapped.as(ZipExporter.class).exportTo(out, true);
        }

        /* for (Map.Entry<ArchivePath, Node> each : wrapped.getContent().entrySet()) {
                System.err.println("-> " + each.getKey());
            }*/

        File executable = File.createTempFile("arquillian", "-swarm.jar");
        wrapped.as(ZipExporter.class).exportTo(executable, true);
        executable.deleteOnExit();


        executor.withProperty("java.net.preferIPv4Stack", "true");
        executor.withExecutableJar(executable.toPath());


        File workingDirectory = Files.createTempDirectory("arquillian").toFile();
        workingDirectory.deleteOnExit();
        executor.withWorkingDirectory(workingDirectory.toPath());

        this.process = executor.execute();
        this.process.getOutputStream().close();

        this.process.awaitDeploy(2, TimeUnit.MINUTES);

        if (!this.process.isAlive()) {
            throw new DeploymentException("Process failed to start");
        }
        if (this.process.getError() != null) {
            throw new DeploymentException("Error starting process", this.process.getError());
        }
    }

    @Override
    public void stop() throws Exception {
        this.process.stop();
    }

    private final Class<?> testClass;
    private SwarmProcess process;
    private Set<String> requestedMavenArtifacts;


}

