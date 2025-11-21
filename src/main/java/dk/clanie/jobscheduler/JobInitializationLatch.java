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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * Provides a coordination mechanism to ensure JobScheduler waits for JobInitializer
 * to complete its initial job scan before starting to execute jobs.
 */
@Slf4j
public class JobInitializationLatch {


	private final CountDownLatch latch = new CountDownLatch(1);


	/**
	 * Called by JobInitializer when job scan is complete.
	 */
	public void countDown() {
		latch.countDown();
		log.debug("Job initialization latch released.");
	}


	/**
	 * Called by JobScheduler to wait for JobInitializer to complete.
	 */
	public void await() {
		if (latch.getCount() > 0) {
			log.debug("Waiting for job initialization to complete...");
			try {
				latch.await(30, TimeUnit.SECONDS);
				if (latch.getCount() > 0) {
					log.error("Job initialization did not complete within timeout - proceeding anyway.");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.warn("Interrupted while waiting for job initialization to complete - proceeding anyway.");
			}
		}
	}


}
