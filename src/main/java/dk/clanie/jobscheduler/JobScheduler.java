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

import static dk.clanie.core.Utils.opt;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JobScheduler {


	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private JobRepository jobRepository;

	@Autowired
	private JobExecutionService jobExecutionService;

	@Autowired(required = false)
	private JobInitializationLatch initializationLatch;


	@Value(value = "${jobScheduler.pollInterval:PT1M}")
	private Duration pollInterval;

	@Value("${jobScheduler.maxParallelJobs}")
	private int maxParallelJobs;

	@Value("${jobScheduler.exitWhenIdle:false}")
	private boolean exitWhenIdle;


	private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();


	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() throws Exception {
		executorService.submit(() -> {
			opt(initializationLatch).ifPresent(JobInitializationLatch::await); // Wait for JobInitializer to complete (if present)
			log.info("Job scheduler started with max {} parallel jobs, polling every {}.", maxParallelJobs, pollInterval);
			Semaphore semaphore = new Semaphore(maxParallelJobs);
			while (true) {
				semaphore.acquire();  // Block if too many jobs are running
				try {
					jobRepository.popForExecution().ifPresentOrElse(
							job -> {
								submit(job, semaphore); // This will release a permit when done
							},
							() -> {
								semaphore.release();  // No job was found - release permit
								if (exitWhenIdle && semaphore.availablePermits() == maxParallelJobs) {
									// Ask Spring Boot to shutdown; this will cause the JVM to exit with the given code.
									log.info("No jobs found and exitWhenIdle is set - shutting down.");
									SpringApplication.exit(applicationContext, () -> 0);
								} else {
									sleep();
								}
							});
				} catch (Exception e) {
					log.error("Job scheduler failed.", e);
					Thread.sleep(Duration.ofMinutes(10).toMillis());
				}
			}
		});
	}


	private void submit(Job job, Semaphore semaphore) {
		String displayName = job.getName().displayName();
		log.debug("Submitting job {}.", displayName);
		executorService.submit(() -> {
			try {
				jobExecutionService.execute(job);
			} finally {
				semaphore.release();  // Release permit when job is done
			}
		});
	}


	private void sleep() {
		Duration delay = jobRepository.findNextExecutionTime()
				.map(nextExecutionTime -> {
					Duration durationUntilNextPlannedExecution = Duration.between(ZonedDateTime.now(), nextExecutionTime);
					log.trace("Duration until next currently planned Job execution is: {}.", durationUntilNextPlannedExecution);
					// Next planned execution may be far off, and manually scheduled or modified jobs may need to be scheduled
					// before then, so sleep at most the configured poll interval.
					return durationUntilNextPlannedExecution.compareTo(pollInterval) < 0 ? durationUntilNextPlannedExecution : pollInterval;
				})
				.orElse(pollInterval);
		try {
			log.trace("Sleeping for {}.", delay);
			Thread.sleep(delay);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Job scheduler interrupted", e);
		}
	}


}
