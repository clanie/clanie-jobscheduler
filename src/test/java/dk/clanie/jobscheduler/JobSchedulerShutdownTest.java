/*
 * Copyright (C) 2026, Claus Nielsen, clausn999@gmail.com
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * A blue-green swap closes the context while the poll loop is inside {@code popForExecution}
 * (clanie-comealong#103). The loop must stop there, quietly, instead of logging the torn-down
 * Mongo session as an unhandled error.
 */
class JobSchedulerShutdownTest {

	private final ApplicationContext context = mock(ApplicationContext.class);
	private final JobRepository jobRepository = mock(JobRepository.class);
	private final JobScheduler scheduler = new JobScheduler();
	private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
	private final Logger logger = (Logger) LoggerFactory.getLogger(JobScheduler.class);


	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(scheduler, "applicationContext", context);
		ReflectionTestUtils.setField(scheduler, "jobRepository", jobRepository);
		ReflectionTestUtils.setField(scheduler, "jobExecutionService", mock(JobExecutionService.class));
		ReflectionTestUtils.setField(scheduler, "environment", mock(Environment.class));
		ReflectionTestUtils.setField(scheduler, "pollInterval", Duration.ofMinutes(1));
		ReflectionTestUtils.setField(scheduler, "maxParallelJobs", 2);
		ReflectionTestUtils.setField(scheduler, "shutdownTimeout", Duration.ofSeconds(5));
		logged.start();
		logger.addAppender(logged);
	}


	@AfterEach
	void tearDown() {
		logger.detachAppender(logged);
	}


	@Test
	void closingTheContextDuringAPollStopsTheLoopWithoutAnError() throws Exception {
		CountDownLatch polling = new CountDownLatch(1);
		when(jobRepository.popForExecution(any())).thenAnswer(_ -> {
			polling.countDown();
			try {
				Thread.sleep(Duration.ofMinutes(1));
			} catch (InterruptedException e) {
				// What the driver reports once the client is gone under it.
				throw new IllegalStateException("state should be: open", e);
			}
			return Optional.empty();
		});

		scheduler.onApplicationReady();
		assertThat(polling.await(5, TimeUnit.SECONDS)).isTrue();

		scheduler.onContextClosed(new ContextClosedEvent(context));
		Thread pollThread = (Thread) ReflectionTestUtils.getField(scheduler, "pollThread");
		assertThat(pollThread).isNotNull();
		assertThat(pollThread.join(Duration.ofSeconds(5))).isTrue();
		scheduler.destroy();

		verify(jobRepository, times(1)).popForExecution(any());
		assertThat(logged.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
	}


	@Test
	void aChildContextClosingDoesNotStopTheScheduler() throws Exception {
		CountDownLatch polled = new CountDownLatch(2);
		when(jobRepository.popForExecution(any())).thenAnswer(_ -> {
			polled.countDown();
			return Optional.empty();
		});
		when(jobRepository.findNextExecutionTime(any())).thenReturn(Optional.empty());
		ReflectionTestUtils.setField(scheduler, "pollInterval", Duration.ofMillis(10));

		scheduler.onApplicationReady();
		scheduler.onContextClosed(new ContextClosedEvent(mock(ApplicationContext.class)));

		assertThat(polled.await(5, TimeUnit.SECONDS)).isTrue();
		scheduler.destroy();
	}


}
