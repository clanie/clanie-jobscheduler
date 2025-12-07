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

import static dk.clanie.core.Utils.stackTraceOf;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobExecutionService {

	private final ApplicationContext applicationContext;
	private final JobRepository jobRepository;
	private final JobExecutionRepository jobExecutionRepository;


	private record BeanAndMethod(Object bean, Method method) {
		public void invoke() throws IllegalAccessException, InvocationTargetException {
			method.invoke(bean);
		}
	}
	private final Map<JobName, BeanAndMethod> methodsByJobName = new ConcurrentHashMap<>();



	/**
	 * Executes a job with automatic tracking and MDC context.
	 * <p>
	 * This method applies the Job MDC context, runs the job, and automatically
	 * records the execution result (success or failure) in the database.
	 * 
	 * @param job the Job to execute
	 */
	public void execute(Job job) {
		UUID jobExecutionId = job.getJobExecutionId();
		String displayName = job.getName().displayName();
		JobMdc.applyAndRun(jobExecutionId, displayName, () -> {
			BeanAndMethod beanAndMethod = methodsByJobName.computeIfAbsent(job.getName(), this::findBeanAndMethod);
			try {
				beanAndMethod.invoke();
				log.debug("Job {} completed successfully.", displayName);
				jobExecutionRepository.save(JobExecution.of(job, true, null)); // Record success while job still has jobExecutionId
				job.registerCompletedSuccessfully(); // Clears jobExecutionId
			} catch (Exception e) {
				log.error("Job {} failed.", displayName, e);
				jobExecutionRepository.save(JobExecution.of(job, false, stackTraceOf(e))); // Record failure while job still has jobExecutionId
				job.registerFailed(); // Clears jobExecutionId
			}
			jobRepository.save(job);
		});
	}


	/**
	 * Finds job executions for a specific job.
	 * 
	 * @param tenantId the tenant ID
	 * @param jobId the ID of the job
	 * @param pageable pagination information
	 * @return list of job executions
	 */
	public List<JobExecution> findByJobId(UUID tenantId, UUID jobId, Pageable pageable) {
		return jobExecutionRepository.findByJobId(tenantId, jobId, pageable);
	}


	/**
	 * Finds a specific job execution by its execution ID.
	 * 
	 * @param tenantId the tenant ID
	 * @param jobExecutionId the execution ID
	 * @return optional containing the job execution if found
	 */
	public Optional<JobExecution> findByJobExecutionId(UUID tenantId, UUID jobExecutionId) {
		return jobExecutionRepository.findByJobExecutionId(tenantId, jobExecutionId);
	}


	/**
	 * Finds all job executions for a tenant.
	 * 
	 * @param tenantId the tenant ID
	 * @param pageable pagination information
	 * @return page of job executions
	 */
	public Page<JobExecution> findByTenantId(UUID tenantId, Pageable pageable) {
		return jobExecutionRepository.findByTenantId(tenantId, pageable);
	}


	/**
	 * Finds job executions filtered by success status.
	 * 
	 * @param tenantId the tenant ID
	 * @param success whether to find successful or failed executions
	 * @param pageable pagination information
	 * @return page of job executions
	 */
	public Page<JobExecution> findBySuccess(UUID tenantId, boolean success, Pageable pageable) {
		return jobExecutionRepository.findByTenantIdAndSuccess(tenantId, success, pageable);
	}


	private BeanAndMethod findBeanAndMethod(JobName jobName) {
		try {
			Object bean = applicationContext.getBean(jobName.bean());
			Class<?> clazz = bean.getClass();
			Method method = clazz.getMethod(jobName.method());
			return new BeanAndMethod(bean, method);
		} catch (NoSuchMethodException | SecurityException e) {
			throw new RuntimeException("Method not found", e);
		}
	}


}
