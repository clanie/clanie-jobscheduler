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
package dk.clanie.jobscheduling;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

/**
 * Provides a coordination mechanism to ensure JobScheduler waits for JobInitializer
 * to complete its initial job scan before starting to execute jobs.
 */
@Configuration
@Slf4j
public class JobInitializationLatch {

	private final CountDownLatch latch;

	/**
	 * Creates a latch with count 1 if JobInitializer is present, 0 otherwise.
	 */
	public JobInitializationLatch(@Autowired(required = false) JobInitializer jobInitializer) {
		if (jobInitializer != null) {
			log.debug("JobInitializer detected - JobScheduler will wait for initial job scan to complete.");
			this.latch = new CountDownLatch(1);
		} else {
			log.debug("JobInitializer not present - JobScheduler will start immediately.");
			this.latch = new CountDownLatch(0);
		}
	}

	/**
	 * Called by JobInitializer when job scan is complete.
	 */
	public void countDown() {
		latch.countDown();
		log.debug("Job initialization latch released.");
	}

	/**
	 * Called by JobScheduler to wait for JobInitializer to complete.
	 * 
	 * @throws InterruptedException if the wait is interrupted
	 */
	public void await() throws InterruptedException {
		if (latch.getCount() > 0) {
			log.debug("Waiting for job initialization to complete...");
			latch.await(30, TimeUnit.SECONDS);
			if (latch.getCount() > 0) {
				log.error("Job initialization did not complete within timeout - proceeding anyway.");
			}
		}
	}

}
