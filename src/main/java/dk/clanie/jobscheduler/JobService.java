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
			log.info("Creating job: {}", job);
			jobRepository.save(job);
		});

		// Disable obsolete jobs, ie. jobs that are in the repository, but where there is no corresponding annotated method
		Set<JobName> jobNamesForAnnotatedMethods = mapSet(jobInputs, JobInput::name);
		Set<JobName> obsoleteJobNames = filterSet(existingJobNames, not(jobNamesForAnnotatedMethods::contains));
		List<Job> obsoleteJobs = jobRepository.findConfigEnabledJobsByNameIn(applicationName, obsoleteJobNames);
		obsoleteJobs.forEach(job -> {
			log.info("Disabling obsolete job: {} because there is no longer a @ScheduledJob annotation matching it.", job);
			job.setConfigEnabled(false);
		});
		jobRepository.saveAll(obsoleteJobs);

		// Enable / disable jobs according to configuration properties
		Set<JobName> jobNamesEnabledInConfig = new HashSet<>();
		Set<JobName> jobNamesDisabledInConfig = new HashSet<>();
		jobNamesForAnnotatedMethods.forEach(jobName -> {
			String property = "jobScheduler.jobsEnabled." + jobName.bean() + "-" + jobName.method();
			(environment.getRequiredProperty(property, Boolean.class) ? jobNamesEnabledInConfig : jobNamesDisabledInConfig).add(jobName);
		});
		List<Job> jobsToDisable = jobRepository.findConfigEnabledJobsByNameIn(applicationName, jobNamesDisabledInConfig);
		jobsToDisable.forEach(job -> {
			log.info("Disabling job: {} because it was disabled in configuration.", job.getName());
			job.setConfigEnabled(false);
		});
		jobRepository.saveAll(jobsToDisable);
		List<Job> jobsToEnable = jobRepository.findConfigDisabledJobsByNameIn(applicationName, jobNamesEnabledInConfig);
		jobsToEnable.forEach(job -> {
			log.info("Enabling job: {} because it was enabled in configuration.", job.getName());
			job.setConfigEnabled(true);
		});
		jobRepository.saveAll(jobsToEnable);
	}


	private String qualifiedName(Method method) {
		return method.getDeclaringClass().getSimpleName() + "." + method.getName();
	}


}
