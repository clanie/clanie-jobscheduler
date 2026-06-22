/*
 * Copyright (C) 2025, Claus Nielsen, clausn999@gmail.com
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package dk.clanie.jobscheduler;

import static dk.clanie.core.Utils.filterList;
import static dk.clanie.core.Utils.filterSet;
import static dk.clanie.core.Utils.mapSet;
import static dk.clanie.mongo.MongoConstants.ADMIN_TENANT_ID;
import static java.util.function.Predicate.not;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Pageable;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JobService {


	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private Environment environment;

	@Autowired
	private JobRepository jobRepository;


	@Value("${spring.application.name}")
	private String applicationName;

	@Value("${jobScheduler.staleJobThreshold:PT2H}")
	private Duration staleJobThreshold;


	public List<Job> find(UUID tenantId, Pageable pageable, JobFilter filter) {
		return jobRepository.find(tenantId, pageable, filter);
	}


	public long count(UUID tenantId, JobFilter filter) {
		return jobRepository.count(tenantId, filter);
	}


	public void save(Job job) {
		jobRepository.save(job);
	}


	public void delete(UUID tenantId, UUID jobId) {
		jobRepository.deleteById(tenantId, jobId);
	}


	/**
	 * Deletes multiple jobs by their IDs in a single operation.
	 */
	public void deleteByIdIn(UUID tenantId, Collection<UUID> jobIds) {
		jobRepository.deleteByIdIn(tenantId, jobIds);
	}


	/**
	 * Sets a Jobs userEnabled property to given value.
	 * 
	 * @return true if the Job was found and updated.
	 */
	public boolean setUserEnabled(UUID tenantId, UUID id, boolean userEnabled) {
		return jobRepository.setUserEnabled(tenantId, id, userEnabled) == 1;
	}


	/**
	 * Schedules the Job for execution at given time.
	 * <p>
	 * This will be ignored if the job is currently running (ie. if it has a jobExecutionId).
	 * 
	 * @return true if the Job was updated.
	 */
	public boolean setNextExecution(UUID tenantId, UUID id, ZonedDateTime zonedDateTime) {
		return jobRepository.setNextExecution(tenantId, id, zonedDateTime) == 1;
	}


	/**
	 * Clears the running status of a Job by removing poppedForExecution and jobExecutionId.
	 *
	 * @return true if the Job was updated.
	 */
	public boolean clearRunningStatus(UUID tenantId, UUID id) {
		return jobRepository.clearRunningStatus(tenantId, id) == 1;
	}


	/**
	 * Sets the Spring profile of a Job.
	 *
	 * @return true if the Job was updated.
	 */
	public boolean setProfile(UUID tenantId, UUID id, String profile) {
		return jobRepository.setProfile(tenantId, id, profile) == 1;
	}


	/**
	 * Clears the Spring profile of a Job.
	 *
	 * @return true if the Job was updated.
	 */
	public boolean clearProfile(UUID tenantId, UUID id) {
		return jobRepository.clearProfile(tenantId, id) == 1;
	}


	/**
	 * Scans for @ScheduledJob annotated bean methods and creates Jobs for them if they do not already have one.
	 */
	@ScheduledJob()
	public void scanForJobs() {

		record JobInput (
				JobName name,
				Method method,
				ScheduledJob annotation) {
		}

		log.debug("Scanning for @ScheduledJob annotated bean methods.");
		List<JobInput> jobInputs = new ArrayList<>();
		Map<String, Object> beans = applicationContext.getBeansOfType(Object.class, false, false);
		for (Map.Entry<String, Object> entry : beans.entrySet()) {
			String beanName = entry.getKey();
			Object bean = entry.getValue();
			Class<?> targetClass = AopUtils.getTargetClass(bean);
			for (Method method : targetClass.getDeclaredMethods()) {
				ScheduledJob annotation = method.getAnnotation(ScheduledJob.class);
				if (annotation != null) {
					JobName name = new JobName(beanName, method.getName());
					jobInputs.add(new JobInput(name, method, annotation));
				}
			}
		}

		// Create jobs for methods that do not already have one
		Set<JobName> existingJobNames = jobRepository.findNames(applicationName);
		Set<JobName> jobNamesForAnnotatedMethods = mapSet(jobInputs, JobInput::name);
		List<JobInput> inputForMissingJobs = filterList(jobInputs, jobMethod -> !existingJobNames.contains(jobMethod.name()));
		inputForMissingJobs.forEach(jobInput -> {
			Method method = jobInput.method;
			String qualifiedName = qualifiedName(method);
			log.atDebug().log("Processing @ScheduledJob annotated method: {}", qualifiedName);
			if (method.getParameterCount() > 0) {
				throw new IllegalStateException(qualifiedName + " has @ScheduledJob annotation but has parameters. No parameters are allowed.");
			}
			ScheduledJob annotation = jobInput.annotation();
			int scheduleArgsCount = (annotation.cron().isEmpty() ? 0 : 1) + (annotation.delay().isEmpty() ? 0 : 1) + (annotation.rate().isEmpty() ? 0 : 1);
			if (scheduleArgsCount > 1) {
				throw new IllegalStateException(qualifiedName + " has @ScheduledJob annotation with " + scheduleArgsCount + " schedule arguments. Exactly one is required.");
			}
			String scheduleAnnotationUsed = annotation.cron().isEmpty() ? annotation.delay().isEmpty() ? annotation.rate().isEmpty() ? null : "rate" : "delay" : "cron";
			JobSchedule schedule = switch (scheduleAnnotationUsed) {
			case "cron" -> JobSchedule.cron(annotation.cron());
			case "delay" -> JobSchedule.delay(Duration.parse(annotation.delay()));
			case "rate" -> JobSchedule.rate(Duration.parse(annotation.rate()));
			case null -> JobSchedule.manual();
			default -> throw new IllegalStateException("Unexpected value: " + scheduleAnnotationUsed);
			};
			Job job = new Job(ADMIN_TENANT_ID, applicationName, jobInput.name(), schedule);
			String maxFailureDuration = annotation.maxFailureDuration();
			job.setMaxFailureDuration(maxFailureDuration.isEmpty() ? null : Duration.parse(maxFailureDuration));
			log.info("Creating job: {}", job);
			jobRepository.save(job);
		});

		// Atomically sync maxFailureDuration from annotation for existing jobs
		List<JobInput> inputForExistingJobs = filterList(jobInputs, ji -> existingJobNames.contains(ji.name()));
		if (!inputForExistingJobs.isEmpty()) {
			Map<Duration, Set<JobName>> byDuration = new HashMap<>();
			Set<JobName> namesToClearDuration = new HashSet<>();
			inputForExistingJobs.forEach(ji -> {
				String val = ji.annotation().maxFailureDuration();
				if (val.isEmpty()) {
					namesToClearDuration.add(ji.name());
				} else {
					byDuration.computeIfAbsent(Duration.parse(val), _ -> new HashSet<>()).add(ji.name());
				}
			});
			byDuration.forEach((duration, names) -> jobRepository.setMaxFailureDuration(applicationName, names, duration));
			if (!namesToClearDuration.isEmpty()) {
				jobRepository.clearMaxFailureDuration(applicationName, namesToClearDuration);
			}
		}

		// Atomically disable obsolete jobs (in repository but no matching @ScheduledJob annotation)
		Set<JobName> obsoleteJobNames = filterSet(existingJobNames, not(jobNamesForAnnotatedMethods::contains));
		if (!obsoleteJobNames.isEmpty()) {
			log.info("Disabling {} obsolete job(s) with no matching @ScheduledJob annotation: {}.", obsoleteJobNames.size(), obsoleteJobNames);
			jobRepository.setConfigEnabled(applicationName, obsoleteJobNames, false);
		}

		// Atomically enable/disable jobs per configuration properties
		Set<JobName> jobNamesEnabledInConfig = new HashSet<>();
		Set<JobName> jobNamesDisabledInConfig = new HashSet<>();
		jobNamesForAnnotatedMethods.forEach(jobName -> {
			String property = "jobScheduler.jobsEnabled." + jobName.bean() + "-" + jobName.method();
			(environment.getRequiredProperty(property, Boolean.class) ? jobNamesEnabledInConfig : jobNamesDisabledInConfig).add(jobName);
		});
		if (!jobNamesDisabledInConfig.isEmpty()) {
			log.info("Disabling {} job(s) per configuration: {}.", jobNamesDisabledInConfig.size(), jobNamesDisabledInConfig);
			jobRepository.setConfigEnabled(applicationName, jobNamesDisabledInConfig, false);
		}
		if (!jobNamesEnabledInConfig.isEmpty()) {
			log.info("Enabling {} job(s) per configuration: {}.", jobNamesEnabledInConfig.size(), jobNamesEnabledInConfig);
			jobRepository.setConfigEnabled(applicationName, jobNamesEnabledInConfig, true);
		}
	}


	/**
	 * Clears running status of jobs stranded by a JVM crash or failed post-execution save.
	 * Runs hourly; threshold is configurable via {@code jobScheduler.staleJobThreshold} (default 2h).
	 */
	@ScheduledJob(cron = "0 0 * * * *")
	public void clearStaleJobs() {
		ZonedDateTime threshold = ZonedDateTime.now().minus(staleJobThreshold);
		List<Job> staleJobs = jobRepository.findStaleJobs(threshold);
		if (!staleJobs.isEmpty()) {
			staleJobs.forEach(job -> log.warn("Found stale job '{}' (poppedForExecution={}), clearing running status.",
					job.getName().displayName(), job.getPoppedForExecution()));
			jobRepository.clearStaleRunningStatus(threshold);
		}
	}


	private String qualifiedName(Method method) {
		return method.getDeclaringClass().getSimpleName() + "." + method.getName();
	}


}
