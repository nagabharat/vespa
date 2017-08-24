// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.application;

import com.google.common.collect.ImmutableMap;
import com.yahoo.component.Version;
import com.yahoo.config.application.api.DeploymentSpec;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.Zone;
import com.yahoo.vespa.hosted.controller.Controller;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Information about which deployment jobs an application should run and their current status.
 * This is immutable.
 * 
 * @author bratseth
 */
public class DeploymentJobs {

    private final Optional<Long> projectId;
    private final ImmutableMap<JobType, JobStatus> status;
    private final Optional<String> jiraIssueId;
    private final boolean selfTriggering; // TODO: Remove this when no projects are self-triggering.

    /** Creates an empty set of deployment jobs */
    public DeploymentJobs(long projectId) {
        this(Optional.of(projectId), ImmutableMap.of(), Optional.empty(),true);
    }
    
    public DeploymentJobs(Optional<Long> projectId, Collection<JobStatus> jobStatusEntries, Optional<String> jiraIssueId, boolean selfTriggering) {
        this(projectId, asMap(jobStatusEntries), jiraIssueId, selfTriggering);
    }
    
    private DeploymentJobs(Optional<Long> projectId, Map<JobType, JobStatus> status, Optional<String> jiraIssueId, boolean selfTriggering) {
        Objects.requireNonNull(projectId, "projectId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(jiraIssueId, "jiraIssueId cannot be null");
        this.projectId = projectId;
        this.status = ImmutableMap.copyOf(status);
        this.jiraIssueId = jiraIssueId;
        this.selfTriggering = selfTriggering;
    }
    
    private static Map<JobType, JobStatus> asMap(Collection<JobStatus> jobStatusEntries) {
        ImmutableMap.Builder<JobType, JobStatus> b = new ImmutableMap.Builder<>();
        for (JobStatus jobStatusEntry : jobStatusEntries)
            b.put(jobStatusEntry.type(), jobStatusEntry);
        return b.build();
    }

    /** Return a new instance with the given completion */
    public DeploymentJobs withCompletion(JobReport report, Instant notificationTime, Controller controller) {
        Map<JobType, JobStatus> status = new LinkedHashMap<>(this.status);
        status.compute(report.jobType(), (type, job) -> {
            if (job == null) job = JobStatus.initial(report.jobType());
            return job.withCompletion(report.jobError(), notificationTime, controller);
        });
        return new DeploymentJobs(Optional.of(report.projectId()), status, jiraIssueId, report.selfTriggering());
    }

    public DeploymentJobs withTriggering(DeploymentJobs.JobType jobType, 
                                         Version version, 
                                         Optional<ApplicationRevision> revision, 
                                         Instant triggerTime) {
        Map<JobType, JobStatus> status = new LinkedHashMap<>(this.status);
        status.compute(jobType, (type, job) -> {
            if (job == null) job = JobStatus.initial(jobType);
            return job.withTriggering(version, revision, triggerTime);
        });
        return new DeploymentJobs(projectId, status, jiraIssueId, selfTriggering);
    }

    public DeploymentJobs withProjectId(long projectId) {
        return new DeploymentJobs(Optional.of(projectId), status, jiraIssueId, selfTriggering);
    }

    public DeploymentJobs withJiraIssueId(Optional<String> jiraIssueId) {
        return new DeploymentJobs(projectId, status, jiraIssueId, selfTriggering);
    }

    public DeploymentJobs without(JobType job) {
        Map<JobType, JobStatus> status = new HashMap<>(this.status);
        status.remove(job);
        return new DeploymentJobs(projectId, status, jiraIssueId, selfTriggering);
    }
    
    public DeploymentJobs asSelfTriggering(boolean selfTriggering) {
        return new DeploymentJobs(projectId, status, jiraIssueId, selfTriggering);
    }

    /** Returns an immutable map of the status entries in this */
    public Map<JobType, JobStatus> jobStatus() { return status; }

    /** Returns whether this application's deployment jobs trigger each other, and should be left alone, or not. */
    public boolean isSelfTriggering() { return selfTriggering; }

    /** Returns whether this has some job status which is not a success */
    public boolean hasFailures() {
        return status.values().stream().anyMatch(jobStatus -> ! jobStatus.isSuccess());
    }

    /** Returns whether any job is currently in progress */
    public boolean inProgress() {
        return status.values().stream().anyMatch(JobStatus::inProgress);
    }

    /** Returns whether any job is failing for the given change */
    public boolean failingOn(Change change) {
        return status.values().stream().anyMatch(jobStatus -> !jobStatus.isSuccess() && jobStatus.lastCompletedFor(change));
    }

    /** Returns whether change can be deployed to the given environment */
    public boolean isDeployableTo(Environment environment, Optional<Change> change) {
        if (environment == null || !change.isPresent()) {
            return true;
        }
        if (environment == Environment.staging) {
            return isSuccessful(JobType.systemTest, change.get());
        } else if (environment == Environment.prod) {
            return isSuccessful(JobType.stagingTest, change.get());
        }
        return true; // other environments do not have any preconditions
    }
    
    /** Returns the oldest failingSince time of the jobs of this, or null if none are failing */
    public Instant failingSince() {
        Instant failingSince = null;
        for (JobStatus jobStatus : jobStatus().values()) {
            if (jobStatus.isSuccess()) continue;
            if (failingSince == null || failingSince.isAfter(jobStatus.firstFailing().get().at()))
                failingSince = jobStatus.firstFailing().get().at();
        }
        return failingSince;
    }

    /** Returns the time at which the oldest running job started */
    public Optional<Instant> runningSince() {
        return jobStatus().values().stream()
                .filter(JobStatus::inProgress)
                .sorted(Comparator.comparing(jobStatus -> jobStatus.lastTriggered().get().at()))
                .map(jobStatus -> jobStatus.lastTriggered().get().at())
                .findFirst();
    }

    /**
     * Returns the id of the Screwdriver project running these deployment jobs 
     * - or empty when this is not known or does not exist.
     * It is not known until the jobs have run once and reported back to the controller.
     */
    public Optional<Long> projectId() { return projectId; }

    public Optional<String> jiraIssueId() { return jiraIssueId; }

    private boolean isSuccessful(JobType jobType, Change change) {
        return Optional.ofNullable(jobStatus().get(jobType))
                .filter(JobStatus::isSuccess)
                .filter(status -> status.lastCompletedFor(change))
                .isPresent();
    }

    /** Job types that exist in the build system */
    public enum JobType {

        component("component"),
        systemTest("system-test", zone(SystemName.cd, "test", "cd-us-central-1"), zone("test", "us-east-1")),
        stagingTest("staging-test", zone(SystemName.cd, "staging", "cd-us-central-1"), zone("staging", "us-east-3")),
        productionCorpUsEast1("production-corp-us-east-1", zone("prod", "corp-us-east-1")),
        productionUsEast3("production-us-east-3", zone("prod", "us-east-3")),
        productionUsWest1("production-us-west-1", zone("prod", "us-west-1")),
        productionUsCentral1("production-us-central-1", zone("prod", "us-central-1")),
        productionApNortheast1("production-ap-northeast-1", zone("prod", "ap-northeast-1")),
        productionApNortheast2("production-ap-northeast-2", zone("prod", "ap-northeast-2")),
        productionApSoutheast1("production-ap-southeast-1", zone("prod", "ap-southeast-1")),
        productionEuWest1("production-eu-west-1", zone("prod", "eu-west-1")),
        productionCdUsCentral1("production-cd-us-central-1", zone(SystemName.cd, "prod", "cd-us-central-1")),
        productionCdUsCentral2("production-cd-us-central-2", zone(SystemName.cd, "prod", "cd-us-central-2"));

        private final String id;
        private final Map<SystemName, Zone> zones;

        JobType(String id, Zone... zone) {
            this.id = id;
            Map<SystemName, Zone> zones = new HashMap<>();
            for (Zone z : zone) {
                if (zones.containsKey(z.system())) {
                    throw new IllegalArgumentException("A job can only map to a single zone per system");
                }
                zones.put(z.system(), z);
            }
            this.zones = Collections.unmodifiableMap(zones);
        }

        public String id() { return id; }

        /** Returns the zone for this job in the given system, or empty if this job does not have a zone */
        public Optional<Zone> zone(SystemName system) {
            return Optional.ofNullable(zones.get(system));
        }

        /** Returns whether this is a production job */
        public boolean isProduction() { return environment() == Environment.prod; }
        
        /** Returns the environment of this job type, or null if it does not have an environment */
        public Environment environment() {
            switch (this) {
                case component: return null;
                case systemTest: return Environment.test;
                case stagingTest: return Environment.staging;
                default: return Environment.prod;
            }
        }

        /** Returns the region of this job type, or null if it does not have a region */
        public RegionName region(SystemName system) {
            return zone(system).map(Zone::region).orElse(null);
        }

        public static JobType fromId(String id) {
            switch (id) {
                case "component" : return component;
                case "system-test" : return systemTest;
                case "staging-test" : return stagingTest;
                case "production-corp-us-east-1" : return productionCorpUsEast1;
                case "production-us-east-3" : return productionUsEast3;
                case "production-us-west-1" : return productionUsWest1;
                case "production-us-central-1" : return productionUsCentral1;
                case "production-ap-northeast-1" : return productionApNortheast1;
                case "production-ap-northeast-2" : return productionApNortheast2;
                case "production-ap-southeast-1" : return productionApSoutheast1;
                case "production-eu-west-1" : return productionEuWest1;
                case "production-cd-us-central-1" : return productionCdUsCentral1;
                case "production-cd-us-central-2" : return productionCdUsCentral2;
                default : throw new IllegalArgumentException("Unknown job id '" + id + "'");
            }
        }
        
        /** Returns the job type for the given zone, or null if none */
        public static JobType from(SystemName system, com.yahoo.config.provision.Zone zone) {
           for (JobType job : values()) {
               Optional<com.yahoo.config.provision.Zone> jobZone = job.zone(system);
               if (jobZone.isPresent() && jobZone.get().equals(zone))
                   return job;
           }
           return null;
        }

        /** Returns the job job type for the given environment and region or null if none */
        public static JobType from(SystemName system, Environment environment, RegionName region) {
            switch (environment) {
                case test: return systemTest;
                case staging: return stagingTest;
            }
            return from(system, new com.yahoo.config.provision.Zone(environment, region));
        }

        /** Returns the trigger order to use according to deployment spec */
        public static List<JobType> triggerOrder(SystemName system, DeploymentSpec deploymentSpec) {
            return deploymentSpec.zones().stream()
                    .map(declaredZone -> JobType.from(system, declaredZone.environment(),
                                                      declaredZone.region().orElse(null)))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList));
        }

        private static Zone zone(SystemName system, String environment, String region) {
            return new Zone(system, Environment.from(environment), RegionName.from(region));
        }

        private static Zone zone(String environment, String region) {
            return new Zone(Environment.from(environment), RegionName.from(region));
        }
    }

    /** A job report. This class is immutable. */
    public static class JobReport {

        private final ApplicationId applicationId;
        private final JobType jobType;
        private final long projectId;
        private final long buildNumber;
        private final Optional<JobError> jobError;
        private final boolean selfTriggering;
        private final boolean gitChanges;

        public JobReport(ApplicationId applicationId, JobType jobType, long projectId, long buildNumber, Optional<JobError> jobError, boolean selfTriggering, boolean gitChanges) {
            Objects.requireNonNull(applicationId, "ApplicationId can not be null.");
            Objects.requireNonNull(jobType, "JobType can not be null.");

            this.applicationId = applicationId;
            this.projectId = projectId;
            this.jobType = jobType;
            this.buildNumber = buildNumber;
            this.jobError = jobError;
            this.selfTriggering = selfTriggering;
            this.gitChanges = gitChanges;
        }

        public ApplicationId applicationId() { return applicationId; }
        public JobType jobType() { return jobType; }
        public long projectId() { return projectId; }
        public long buildNumber() { return buildNumber; }
        public boolean success() { return !jobError.isPresent(); }
        public Optional<JobError> jobError() { return jobError; }
        public boolean selfTriggering() { return selfTriggering; }
        public boolean gitChanges() { return gitChanges; }

    }

    public enum JobError {
        unknown,
        outOfCapacity;

        public static Optional<JobError> from(boolean success) {
            return Optional.of(success)
                    .filter(b -> !b)
                    .map(ignored -> unknown);
        }
    }

}