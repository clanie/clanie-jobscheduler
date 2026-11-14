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
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

import lombok.extern.slf4j.Slf4j;

/**
 * Polls Mongo for due {@link Job}s and runs them, at most {@code maxParallelJobs} at a time.
 *
 * <p>Shutdown happens in two steps, both before the Mongo client is closed. Polling stops as
 * soon as the application context starts closing ({@link ContextClosedEvent}, published before
 * any lifecycle bean is stopped), so the poll loop never reaches a Mongo client that is being
 * torn down - which is what a blue-green swap used to report as an unhandled
 * {@code ClientSessionException: state should be: open}. Jobs already running are then given
 * until {@code jobScheduler.shutdownTimeout} to finish when this bean is destroyed; the Mongo
 * beans it depends on are destroyed after it, so they are still open meanwhile.
 */
@Slf4j
public class JobScheduler implements DisposableBean {


	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private JobRepository jobRepository;

	@Autowired
	private JobExecutionService jobExecutionService;

	@Autowired(required = false)
	private JobInitializationLatch initializationLatch;

	@Autowired
	private Environment environment;


	@Value(value = "${jobScheduler.pollInterval:PT1M}")
	private Duration pollInterval;

	@Value("${jobScheduler.maxParallelJobs}")
	private int maxParallelJobs;

	@Value("${jobScheduler.exitWhenIdle:false}")
	private boolean exitWhenIdle;

	@Value("${jobScheduler.requireProfile:false}")
	private boolean requireProfile;

	@Value("${jobScheduler.shutdownTimeout:PT10S}")
	private Duration shutdownTimeout;


	/** Runs the jobs. The poll loop has a thread of its own, so stopping it never waits for a job. */
	private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

	private volatile boolean stopping;

	private volatile @Nullable Thread pollThread;

	private Collection<String> matchProfiles;


	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() throws Exception {
		if (stopping) return;
		pollThread = Thread.ofVirtual().name("job-scheduler").start(() -> {
			opt(initializationLatch).ifPresent(JobInitializationLatch::await); // Wait for JobInitializer to complete (if present)
			matchProfiles = requireProfile
					? Arrays.asList(environment.getActiveProfiles())
					: Arrays.asList(null, "");
			log.info("Job scheduler started with max {} parallel jobs, polling every {}.{}", maxParallelJobs, pollInterval,
					requireProfile ? " Require profile: matching " + matchProfiles : "");
			Semaphore semaphore = new Semaphore(maxParallelJobs);
			while (!stopping) {
				try {
					semaphore.acquire();
					boolean permitReleased = false;
					try {
						Optional<Job> job = jobRepository.popForExecution(matchProfiles);
						if (job.isPresent()) {
							submit(job.get(), semaphore); // This will release a permit when done
							permitReleased = true;
						} else {
							semaphore.release();  // No job was found - release permit
							permitReleased = true;
							if (exitWhenIdle && semaphore.availablePermits() == maxParallelJobs) {
								// Ask Spring Boot to shutdown; this will cause the JVM to exit with the given code.
								log.info("No jobs found and exitWhenIdle is set - shutting down.");
								stopping = true;
								SpringApplication.exit(applicationContext, () -> 0);
							} else {
								sleep();
							}
						}
					} catch (Exception e) {
						if (stopping) {
							// The context is closing: whatever failed was torn down under us. Not an error.
							log.debug("Job scheduler poll failed while stopping.", e);
						} else {
							log.error("Job scheduler failed.", e);
							Thread.sleep(Duration.ofMinutes(10).toMillis());
						}
					} finally {
						// Neither path above took ownership of the permit (popForExecution or submit threw),
						// so release it here - otherwise it would be leaked for the lifetime of the instance.
						if (!permitReleased) semaphore.release();
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					log.info("Job scheduler interrupted - stopping.");
					stopping = true;
				}
			}
		});
	}


	/**
	 * Stops polling at the very start of shutdown, while Mongo is still open.
	 */
	@EventListener(ContextClosedEvent.class)
	public void onContextClosed(ContextClosedEvent event) {
		if (event.getApplicationContext() != applicationContext) return; // A child context closing
		stopPolling();
	}


	/**
	 * Gives running jobs until {@code jobScheduler.shutdownTimeout} to finish, then interrupts them.
	 */
	@Override
	public void destroy() throws InterruptedException {
		stopPolling();
		Thread poller = pollThread;
		if (poller != null && poller != Thread.currentThread()) {
			poller.join(shutdownTimeout);
		}
		executorService.shutdown();
		if (!executorService.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
			log.warn("Jobs still running after {} - interrupting them.", shutdownTimeout);
			executorService.shutdownNow();
		}
	}


	private void stopPolling() {
		if (stopping) return;
		stopping = true;
		Thread poller = pollThread;
		// exitWhenIdle closes the context from the poll thread itself, which then simply returns.
		if (poller != null && poller != Thread.currentThread()) {
			poller.interrupt();
		}
		log.info("Job scheduler stopping.");
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
		Duration delay = jobRepository.findNextExecutionTime(matchProfiles)
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
